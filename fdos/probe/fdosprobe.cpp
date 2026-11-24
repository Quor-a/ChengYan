// fdosprobe.c —— FD-OS S0 能力探针（原创，见 docs/FD-OS 方案 第三章）
// 产出 capmap JSON：每个探针 OK / DENIED / ENOSYS / UNKNOWN + errno + 耗时。
// 纪律：不依赖 root、不用 ptrace、失败返回错误码不崩溃；探针写盘内容用后即删。

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <time.h>
#include <pthread.h>
#include <dlfcn.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <sys/socket.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/prctl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <linux/audit.h>
#include <linux/memfd.h>
#include <android/sharedmem.h>
#include <android/dlext.h>
#include <signal.h>

extern char **environ;

#include "fdos_probe42_blob.h"  // fdos_probe42_blob / _len
#include "fdos_dummy_blob.h"   // fdos_dummy_blob / _len
#define probe42_start fdos_probe42_blob
#define probe42_end   (fdos_probe42_blob + fdos_probe42_blob_len)
#define probe_so_start fdos_dummy_blob
#define probe_so_end   (fdos_dummy_blob + fdos_dummy_blob_len)

#ifndef __NR_memfd_create
#define __NR_memfd_create 279
#endif
#ifndef __NR_execveat
#define __NR_execveat 281
#endif
#ifndef __NR_pidfd_open
#define __NR_pidfd_open 434
#endif
#ifndef __NR_pidfd_send_signal
#define __NR_pidfd_send_signal 424
#endif
#ifndef __NR_seccomp
#define __NR_seccomp 277
#endif

typedef enum { P_OK = 0, P_DENIED = 1, P_ENOSYS = 2, P_UNKNOWN = 3 } Pres;
static const char *pres_str(Pres p) {
    switch (p) { case P_OK: return "OK"; case P_DENIED: return "DENIED";
                 case P_ENOSYS: return "ENOSYS"; default: return "UNKNOWN"; }
}

// 嵌入式最小静态 ELF（退出码 42）与 dlopen 用最小 .so：由 build.sh 以 xxd 生成


static long now_ms(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

typedef struct { char name[32]; Pres res; int err; long ms; char note[96]; } Probe;
static Probe probes[12];
static int n_probes = 0;

static void record(const char *name, Pres r, int err, long t0, const char *note) {
    Probe *p = &probes[n_probes++];
    snprintf(p->name, sizeof p->name, "%s", name);
    p->res = r; p->err = err; p->ms = now_ms() - t0;
    snprintf(p->note, sizeof p->note, "%s", note ? note : "");
}

// 在 fork 子进程里跑的探针体：返回 0=成功，>0=失败原因（errno）
typedef int (*probe_fn)(void);

// 子进程统一入口：不允许子进程逃逸，exit 码即结果
static int run_child(probe_fn fn, int timeout_ms) {
    pid_t pid = fork();
    if (pid < 0) return -1;
    if (pid == 0) {
        int r = fn();
        _exit(r == 0 ? 42 : (r == -1 ? 99 : 100 + (r & 0x1f)));
    }
    // 超时守护：简单轮询 waitpid（S0 足够，S1 换 signalfd+epoll）
    long dl = now_ms() + timeout_ms;
    int st = 0;
    while (now_ms() < dl) {
        pid_t w = waitpid(pid, &st, WNOHANG);
        if (w == pid) {
            if (WIFEXITED(st)) return WEXITSTATUS(st) == 42 ? 0 : WEXITSTATUS(st);
            return 98;
        }
        usleep(2000);
    }
    kill(pid, SIGKILL); waitpid(pid, &st, 0);
    return 97; // timeout
}

// ---- 探针实现 ----

static int p_memfd_exec(void) {
    int fd = (int)syscall(__NR_memfd_create, "fdos-probe", (unsigned long)MFD_CLOEXEC);
    if (fd < 0) return errno == ENOSYS ? -1 : errno;
    size_t len = (size_t)(probe42_end - probe42_start);
    if (write(fd, probe42_start, len) != (ssize_t)len) { int e = errno; close(fd); return e; }
    fchmod(fd, 0700);
    lseek(fd, 0, SEEK_SET);
    pid_t pid = fork();
    if (pid == 0) {
        char *argv[] = {"probe42", NULL};
        char *envp[] = {NULL};
        syscall(__NR_execveat, (long)fd, "", argv, envp, (unsigned long)AT_EMPTY_PATH);
        _exit(errno == EACCES ? 101 : errno == EPERM ? 102 : 103);
    }
    int st; waitpid(pid, &st, 0);
    close(fd);
    if (WIFEXITED(st) && WEXITSTATUS(st) == 42) return 0;
    return WIFEXITED(st) ? (WEXITSTATUS(st) - 100) : 101;
}

static int p_memfd_noexec_probe(void) {
    // 读 sysctl + 尝试 MFD_EXEC(0x10)
    int v = -1;
    int f = open("/proc/sys/vm/memfd_noexec", O_RDONLY);
    if (f >= 0) { char b[8] = {0}; read(f, b, sizeof b - 1); close(f); v = atoi(b); }
    return v >= 0 ? v : -2; // 成功返回 sysctl 值（0/1/2），-2 = 读不到
}

static int p_pidfd(void) {
    int fd = (int)syscall(__NR_pidfd_open, getpid(), (unsigned long)0);
    if (fd < 0) return errno == ENOSYS ? -1 : errno;
    close(fd);
    return 0;
}

static int p_scm_rights(void) {
    int sv[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) < 0) return errno;
    int mfd = (int)syscall(__NR_memfd_create, "scm", (unsigned long)MFD_CLOEXEC);
    write(mfd, "ok", 2);
    pid_t pid = fork();
    if (pid == 0) {
        char buf[1]; int nf = -1;
        char cbuf[CMSG_SPACE(sizeof(int))];
        struct iovec iov = { (void *)buf, 1 };
        struct msghdr mh; memset(&mh, 0, sizeof mh);
        mh.msg_iov = &iov; mh.msg_iovlen = 1;
        mh.msg_control = cbuf; mh.msg_controllen = sizeof cbuf;
        // 父进程先发
        usleep(20000);
        if (recvmsg(sv[1], &mh, 0) < 1) _exit(95);
        struct cmsghdr *cm = CMSG_FIRSTHDR(&mh);
        if (!cm || cm->cmsg_len < CMSG_LEN(sizeof(int))) _exit(94);
        memcpy(&nf, CMSG_DATA(cm), sizeof(int));
        char b2[2] = {0};
        if (nf >= 0) { ssize_t n = read(nf, b2, 2); _exit(n == 2 && b2[0] == 'o' ? 42 : 93); }
        _exit(92);
    }
    char cbuf[CMSG_SPACE(sizeof(int))];
    struct iovec iov = { (void *)"x", 1 };
    struct msghdr mh; memset(&mh, 0, sizeof mh);
    mh.msg_iov = &iov; mh.msg_iovlen = 1;
    mh.msg_control = cbuf; mh.msg_controllen = sizeof cbuf;
    struct cmsghdr *cm = CMSG_FIRSTHDR(&mh);
    cm->cmsg_level = SOL_SOCKET; cm->cmsg_type = SCM_RIGHTS; cm->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cm), &mfd, sizeof(int));
    sendmsg(sv[0], &mh, 0);
    int st; waitpid(pid, &st, 0);
    close(sv[0]); close(sv[1]); close(mfd);
    return (WIFEXITED(st) && WEXITSTATUS(st) == 42) ? 0 : WIFEXITED(st) ? WEXITSTATUS(st) - 80 : EIO;
}

static int p_seccomp_stack(void) {
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) < 0) return errno;
    // allow-all filter（只验证"能否叠加"，不收紧）
    struct sock_filter ins[] = { BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ALLOW) };
    struct sock_fprog prog = { 1, ins };
    if (syscall(__NR_seccomp, (unsigned long)SECCOMP_SET_MODE_FILTER,
                (unsigned long)0, &prog) < 0) return errno;
    return 0;
}

static int p_ashmem(void) {
    int fd = ASharedMemory_create("fdos-ashmem", 4096);
    if (fd < 0) return errno ? errno : ENOSYS;
    char *m = (char *)mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (m == MAP_FAILED) { int e = errno; close(fd); return e; }
    m[0] = 7;
    close(fd);
    munmap(m, 4096);
    return 0;
}

static int p_bionic_dlext_fd(void) {
    // 需要 NDK 链接 libdl；直接 dlsym android_dlopen_ext
    void *(*dlopen_ext)(const char *, int, const android_dlextinfo *) =
        (void *(*)(const char *, int, const android_dlextinfo *))dlsym(RTLD_DEFAULT, "android_dlopen_ext");
    if (!dlopen_ext) return ENOSYS;
    // 准备一个可 dlopen 的最小 .so：由 assets 提供（libfdosdummy.so，随探针库打包在同目录）
    // S0 首版用 memfd+USE_LIBRARY_FD 直接试：写一个 trampoline .so 进 memfd
    // dummy .so 由 build 脚本生成并嵌入（fdos_dummy_blob）
    int mfd = (int)syscall(__NR_memfd_create, "fdos-dso", (unsigned long)MFD_CLOEXEC);
    size_t len = fdos_dummy_blob_len;
    if (write(mfd, fdos_dummy_blob, len) != (ssize_t)len) { int e = errno; close(mfd); return e; }
    android_dlextinfo info;
    memset(&info, 0, sizeof info);
    info.flags = ANDROID_DLEXT_USE_LIBRARY_FD;
    info.library_fd = mfd;
    void *h = dlopen_ext("/fdos/dummy.so", RTLD_NOW, &info);
    if (!h) { close(mfd); return EACCES; } // denied 或格式拒
    void *sym = dlsym(h, "fdos_dummy_mark");
    int ok = (sym != NULL);
    dlclose(h); close(mfd);
    return ok ? 0 : ENOENT;
}

static int p_jni_exec(void) {
    // nativeLibraryDir 由 env 传入（FDOS_LIBDIR），exec 自己
    const char *libdir = getenv("FDOS_LIBDIR");
    if (!libdir) return 96;
    char path[512];
    snprintf(path, sizeof path, "%s/libfdosprobe.so", libdir);
    pid_t pid = fork();
    if (pid == 0) {
        char *argv[] = {"fdosprobe", "--probe-exec", NULL};
        execve(path, argv, environ);
        _exit(errno == EACCES ? 101 : errno == EPERM ? 102 : 103);
    }
    int st; waitpid(pid, &st, 0);
    if (WIFEXITED(st) && WEXITSTATUS(st) == 42) return 0;
    return WIFEXITED(st) ? WEXITSTATUS(st) - 100 : EACCES;
}

static int p_appdata_exec(void) {
    const char *dir = getenv("FDOS_DATA");
    if (!dir) return 96;
    char path[512];
    snprintf(path, sizeof path, "%s/.fdos_probe_exec", dir);
    int f = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0700);
    if (f < 0) return errno;
    size_t len = (size_t)(probe42_end - probe42_start);
    if (write(f, probe42_start, len) != (ssize_t)len) { int e = errno; close(f); unlink(path); return e; }
    close(f);
    pid_t pid = fork();
    if (pid == 0) { char *argv[] = {"probe42", NULL}; execve(path, argv, environ); _exit(errno == EACCES ? 101 : errno == EPERM ? 102 : 103); }
    int st; waitpid(pid, &st, 0);
    unlink(path);
    if (WIFEXITED(st) && WEXITSTATUS(st) == 42) return 0;
    return WIFEXITED(st) ? WEXITSTATUS(st) - 100 : EACCES;
}

// ---- 主入口 ----

static void run_all(const char *datadir, const char *libdir, int debug_appdata) {
    n_probes = 0;
    memset(probes, 0, sizeof probes);
    long t0;

    t0 = now_ms();
    setenv("FDOS_LIBDIR", libdir, 1);
    setenv("FDOS_DATA", datadir, 1);
    int r = run_child(p_memfd_exec, 1000);
    record("MEMFD_EXEC", r == 0 ? P_OK : (r == -1 ? P_ENOSYS : P_DENIED), r == 0 ? 0 : errno, t0,
           r == 0 ? "memfd+execveat(AT_EMPTY_PATH) exit=42" : "execveat 被拒或子进程异常");

    t0 = now_ms();
    int v = p_memfd_noexec_probe();
    record("MEMFD_NOEXEC", v == -1 ? P_UNKNOWN : P_OK, v, t0,
           v >= 0 ? "vm.memfd_noexec 值(0=可exec,1/2=noexec)" : "sysctl 不可读（老内核）");

    t0 = now_ms();
    r = run_child(p_pidfd, 500);
    record("PIDFD", r == 0 ? P_OK : (r == -1 ? P_ENOSYS : P_DENIED), r == 0 ? 0 : errno, t0,
           "pidfd_open(getpid())");

    t0 = now_ms();
    r = run_child(p_scm_rights, 1000);
    record("SCM_RIGHTS", r == 0 ? P_OK : P_DENIED, r == 0 ? 0 : errno, t0,
           "socketpair+sendmsg fd 跨进程");

    t0 = now_ms();
    r = run_child(p_seccomp_stack, 500);
    record("SECCOMP_STACK", r == 0 ? P_OK : P_DENIED, r == 0 ? 0 : errno, t0,
           "no_new_privs + 叠加 allow-all filter");

    t0 = now_ms();
    r = run_child(p_ashmem, 500);
    record("ASHMEM", r == 0 ? P_OK : (r == -1 ? P_ENOSYS : P_DENIED), r == 0 ? 0 : errno, t0,
           "ASharedMemory_create+mmap");

    t0 = now_ms();
    r = run_child(p_bionic_dlext_fd, 1000);
    record("BIONIC_DLEXT_FD", r == 0 ? P_OK : (r == -1 ? P_ENOSYS : P_DENIED), r == 0 ? 0 : errno, t0,
           "android_dlopen_ext(USE_LIBRARY_FD) 从 memfd");

    t0 = now_ms();
    r = run_child(p_jni_exec, 1000);
    record("JNI_EXEC", r == 0 ? P_OK : P_DENIED, r == 0 ? 0 : errno, t0,
           "execve(nativeLibraryDir/libfdosprobe.so)");

    if (debug_appdata) {
        t0 = now_ms();
        r = run_child(p_appdata_exec, 1000);
        record("APPDATA_EXEC", r == 0 ? P_OK : P_DENIED, r == 0 ? 0 : errno, t0,
               "execve(filesDir/x)——仅 debug 开启");
    } else {
        record("APPDATA_EXEC", P_UNKNOWN, 0, t0, "跳过（capmap.debug 未开）");
    }

    record("INTERP_FD", P_UNKNOWN, 0, now_ms(), "待 S0.2：需 musl ld.so 探针（PT_INTERP=/proc/self/fd/N）");
    record("VPN_TUN", P_UNKNOWN, 0, now_ms(), "待 UI 授权实测（VpnService.establish）");
}

static char g_json[8192];
static void build_json(const char *extra) {
    char *w = g_json; size_t cap = sizeof g_json;
    int n = snprintf(w, cap, "{\"generated\":%ld,\"probes\":[", (long)time(NULL));
    w += n; cap -= n;
    for (int i = 0; i < n_probes; i++) {
        Probe *p = &probes[i];
        n = snprintf(w, cap, "%s{\"name\":\"%s\",\"result\":\"%s\",\"errno\":%d,\"ms\":%ld,\"note\":\"%s\"}",
                     i ? "," : "", p->name, pres_str(p->res), p->err, p->ms, p->note);
        w += n; cap -= n;
    }
    n = snprintf(w, cap, "]%s}", extra ? extra : "");
    (void)n;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_chengyan_app_data_FdosProbe_runAll(JNIEnv *env, jclass, jstring dataDir, jstring libDir,
                                            jboolean debugAppdata) {
    const char *dd = env->GetStringUTFChars(dataDir, NULL);
    const char *ld = env->GetStringUTFChars(libDir, NULL);
    run_all(dd, ld, debugAppdata == JNI_TRUE);
    build_json(NULL);
    env->ReleaseStringUTFChars(dataDir, dd);
    env->ReleaseStringUTFChars(libDir, ld);
    return env->NewStringUTF(g_json);
}
