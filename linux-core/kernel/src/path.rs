//! guest 路径 → 宿主路径的统一解析（jail 内）。
//!
//! 这是"终端能不能用"的地基：busybox 的每个 applet 都按 cwd 解释相对路径，
//! 旧实现把所有相对路径直接拼到 root 上（等于 cwd 永远是 "/"），所以
//! `mkdir d && cd d && touch f` 一定会把 f 落到 jail 根，用户看到的就是
//! "cd 没用 / 文件跑错地方"。这里把 cwd 引入解析链，并处理两件必须的事：
//!
//! 1. `.` / `..` 在虚拟路径层规范化——`..` pop 到空即止，天然不能越狱；
//! 2. rootfs 里绝大多数 applet 是**绝对**软链（`/bin/sh -> /bin/busybox`）。
//!    若原样交给宿主，宿主会去解析自己根下的 /bin/busybox（Alpine/Android
//!    上那个路径指向别的东西，甚至不存在）。所以绝对链接目标必须重定向回
//!    jail 内，并且在 jail 内跟随，最多 16 跳。

use std::path::{Path, PathBuf};

/// 软链跟随上限（与 Linux 的 MAXSYMLINKS 同量级）。
const MAX_SYMLINKS: usize = 16;

/// 把虚拟绝对路径（已规范化的组件序列）拼成宿主路径。
pub fn join_root(root: &Path, comps: &[String]) -> PathBuf {
    let mut p = root.to_path_buf();
    for c in comps {
        p.push(c);
    }
    p
}

/// 规范化虚拟路径：base 为起始组件（cwd），raw 为待解析路径。
/// 返回纯组件序列——不做任何宿主访问，因此永远安全。
pub fn virt_components(cwd: &[String], raw: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    // 相对路径从 cwd 起步；绝对路径从根起步
    if !raw.starts_with('/') {
        out.extend(cwd.iter().cloned());
    }
    for comp in raw.split('/') {
        match comp {
            "" | "." => {}
            ".." => {
                out.pop();
            }
            c => out.push(c.to_string()),
        }
    }
    out
}

/// cwd 字符串（"/"、"/tmp"）→ 组件序列
pub fn split_abs(cwd: &str) -> Vec<String> {
    cwd.split('/')
        .filter(|s| !s.is_empty() && *s != ".")
        .map(|s| s.to_string())
        .collect()
}

/// 组件序列 → 绝对虚拟路径字符串
pub fn to_virt(comps: &[String]) -> String {
    if comps.is_empty() {
        "/".to_string()
    } else {
        format!("/{}", comps.join("/"))
    }
}

/// 读软链目标；绝对目标重定向为 jail 内的虚拟绝对路径。
/// 返回 (目标虚拟组件是否从根起步, 目标字符串)
pub fn link_target_inside(root: &Path, host: &Path) -> Option<String> {
    let t = std::fs::read_link(host).ok()?;
    let s = t.to_string_lossy().to_string();
    if s.starts_with('/') {
        // 绝对链接：留在 jail 内（root 本身对应的就是 guest 的 "/"）
        Some(s)
    } else {
        // 相对链接：相对所在目录
        let mut comps: Vec<String> = Vec::new();
        let rel = host.strip_prefix(root).ok()?;
        for c in rel.parent()?.components() {
            if let std::path::Component::Normal(n) = c {
                comps.push(n.to_string_lossy().to_string());
            }
        }
        comps.extend(virt_components(&[], &s));
        Some(to_virt(&comps))
    }
}

/// 在 jail 内跟随软链，最多 MAX_SYMLINKS 跳。
/// `follow_last=false` 用于 lstat 语义（末段不跟随）。
/// 返回宿主路径；越狱或跳数超限返回 None。
pub fn resolve_following(root: &Path, start_comps: &[String], follow_last: bool) -> Option<PathBuf> {
    let mut comps: Vec<String> = start_comps.to_vec();
    let mut hops = 0usize;
    let mut i = 0usize;
    loop {
        if i >= comps.len() {
            return Some(join_root(root, &comps));
        }
        let cur = join_root(root, &comps[..=i]);
        let is_last = i + 1 == comps.len();
        let md = match std::fs::symlink_metadata(&cur) {
            Ok(m) => m,
            Err(_) => {
                // 不存在：后面的段落无从判断软链，直接返回拼接结果（调用方拿 ENOENT）
                return Some(join_root(root, &comps));
            }
        };
        if md.file_type().is_symlink() && (follow_last || !is_last) {
            hops += 1;
            if hops > MAX_SYMLINKS {
                return None;
            }
            let target = link_target_inside(root, &cur)?;
            let mut tcomps = virt_components(&[], &target);
            // 剩余的待解析段接在链接目标之后
            let rest: Vec<String> = comps[i + 1..].to_vec();
            tcomps.extend(rest);
            comps = tcomps;
            i = 0;
            continue;
        }
        i += 1;
    }
}
