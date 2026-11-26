//! 宿主侧 shell：把一行命令解析成「管道 / 重定向 / 顺序 / 条件」，逐段用 busybox applet 执行。
//!
//! 为什么必须在宿主侧做：真机上的引擎是「一次一个 busybox applet」的无进程模型，
//! 没有 clone/fork/execve/pipe syscall，`sh -c "ls | wc -l"` 在 guest 里根本跑不动。
//! 旧实现更粗暴——把整行按空格切开，直接 `busybox <第一个词> <其余>`，于是
//! `cd`、`|`、`>`、`&&`、`$VAR`、`*` 全部当成普通参数，用户看到的就是
//! 「整个终端都不行」。这里补上 shell 语义，guest 每次只负责跑一个 applet。
//!
//! 支持：`|` 管道 / `>` `>>` `<` `2>` `2>>` `2>&1` `&>` / `;` `&&` `||` /
//! 单双引号 / `\` 转义 / `$VAR` `${VAR}` `$?` `$PWD` `$HOME` / `~` / glob `* ? []` /
//! 内建 `cd pwd export unset exit true false`。
//! 不支持（后续）：`$(...)` 命令替换、`for/while/if`、函数、后台 `&`。

use crate::{Session, SharedOut};
use std::collections::BTreeMap;
use std::path::Path;
use std::sync::atomic::Ordering;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Op {
    Pipe,
    And,
    Or,
    Seq,
    Out,
    OutAppend,
    In,
    ErrOut,
    ErrAppend,
    Both,
    BothAppend,
    MergeErr,
    Background,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Q {
    /// 单引号 / 反斜杠转义：完全字面
    Literal,
    /// 双引号 / 裸词：做变量展开（裸词再做 glob）
    Expand,
    /// 裸词：变量展开 + glob
    Bare,
}

#[derive(Clone, Debug)]
struct Seg {
    text: String,
    q: Q,
}

#[derive(Clone, Debug, Default)]
struct Word {
    segs: Vec<Seg>,
}

impl Word {
    fn push(&mut self, q: Q, s: &str) {
        if let Some(last) = self.segs.last_mut() {
            if last.q == q && q != Q::Bare {
                last.text.push_str(s);
                return;
            }
        }
        self.segs.push(Seg { text: s.to_string(), q });
    }
    fn push_char(&mut self, q: Q, c: char) {
        let mut b = [0u8; 4];
        self.push(q, c.encode_utf8(&mut b));
    }
}

#[derive(Clone, Debug)]
enum Tok {
    W(Word),
    O(Op),
}

fn lex(src: &str) -> Result<Vec<Tok>, String> {
    let cs: Vec<char> = src.chars().collect();
    let mut i = 0usize;
    let mut toks: Vec<Tok> = Vec::new();
    let mut cur: Option<Word> = None;

    macro_rules! flush {
        () => {
            if let Some(w) = cur.take() {
                toks.push(Tok::W(w));
            }
        };
    }

    while i < cs.len() {
        let c = cs[i];
        let nx = cs.get(i + 1).copied();
        match c {
            ' ' | '\t' | '\r' => {
                flush!();
                i += 1;
            }
            '\n' => {
                flush!();
                toks.push(Tok::O(Op::Seq));
                i += 1;
            }
            '#' if cur.is_none() => {
                while i < cs.len() && cs[i] != '\n' {
                    i += 1;
                }
            }
            '\'' => {
                i += 1;
                let mut s = String::new();
                while i < cs.len() && cs[i] != '\'' {
                    s.push(cs[i]);
                    i += 1;
                }
                if i >= cs.len() {
                    return Err("未闭合的单引号".into());
                }
                i += 1;
                cur.get_or_insert_with(Word::default).push(Q::Literal, &s);
            }
            '"' => {
                i += 1;
                let mut s = String::new();
                while i < cs.len() && cs[i] != '"' {
                    if cs[i] == '\\' && i + 1 < cs.len() {
                        let n = cs[i + 1];
                        // 双引号内只对 $ ` " \ 换行 生效
                        if matches!(n, '$' | '`' | '"' | '\\') {
                            s.push(n);
                            i += 2;
                            continue;
                        }
                        if n == '\n' {
                            i += 2;
                            continue;
                        }
                        s.push('\\');
                        i += 1;
                        continue;
                    }
                    s.push(cs[i]);
                    i += 1;
                }
                if i >= cs.len() {
                    return Err("未闭合的双引号".into());
                }
                i += 1;
                cur.get_or_insert_with(Word::default).push(Q::Expand, &s);
            }
            '\\' => {
                if i + 1 < cs.len() {
                    if cs[i + 1] != '\n' {
                        cur.get_or_insert_with(Word::default).push_char(Q::Literal, cs[i + 1]);
                    }
                    i += 2;
                } else {
                    i += 1;
                }
            }
            '|' => {
                flush!();
                if nx == Some('|') {
                    toks.push(Tok::O(Op::Or));
                    i += 2;
                } else {
                    toks.push(Tok::O(Op::Pipe));
                    i += 1;
                }
            }
            '&' => {
                flush!();
                if nx == Some('&') {
                    toks.push(Tok::O(Op::And));
                    i += 2;
                } else if nx == Some('>') {
                    if cs.get(i + 2) == Some(&'>') {
                        toks.push(Tok::O(Op::BothAppend));
                        i += 3;
                    } else {
                        toks.push(Tok::O(Op::Both));
                        i += 2;
                    }
                } else {
                    toks.push(Tok::O(Op::Background));
                    i += 1;
                }
            }
            ';' => {
                flush!();
                toks.push(Tok::O(Op::Seq));
                i += 1;
            }
            '>' => {
                flush!();
                if nx == Some('>') {
                    toks.push(Tok::O(Op::OutAppend));
                    i += 2;
                } else {
                    toks.push(Tok::O(Op::Out));
                    i += 1;
                }
            }
            '<' => {
                flush!();
                toks.push(Tok::O(Op::In));
                i += 1;
            }
            // fd 前置重定向：2> 2>> 2>&1（仅当 2 是独立词首）
            '2' if cur.is_none() && nx == Some('>') => {
                if cs.get(i + 2) == Some(&'&') && cs.get(i + 3) == Some(&'1') {
                    toks.push(Tok::O(Op::MergeErr));
                    i += 4;
                } else if cs.get(i + 2) == Some(&'>') {
                    toks.push(Tok::O(Op::ErrAppend));
                    i += 3;
                } else {
                    toks.push(Tok::O(Op::ErrOut));
                    i += 2;
                }
            }
            _ => {
                cur.get_or_insert_with(Word::default).push_char(Q::Bare, c);
                i += 1;
            }
        }
    }
    flush!();
    Ok(toks)
}

#[derive(Clone, Debug, Default)]
struct CmdSpec {
    argv: Vec<String>,
    out: Option<(String, bool)>,
    errf: Option<(String, bool)>,
    inp: Option<String>,
    merge: bool,
}

fn expand_vars(s: &str, env: &BTreeMap<String, String>, last: i32) -> String {
    let cs: Vec<char> = s.chars().collect();
    let mut out = String::new();
    let mut i = 0usize;
    while i < cs.len() {
        if cs[i] != '$' {
            out.push(cs[i]);
            i += 1;
            continue;
        }
        if i + 1 >= cs.len() {
            out.push('$');
            break;
        }
        let n = cs[i + 1];
        if n == '?' {
            out.push_str(&last.to_string());
            i += 2;
            continue;
        }
        if n == '$' {
            out.push_str(&std::process::id().to_string());
            i += 2;
            continue;
        }
        if n == '{' {
            let mut j = i + 2;
            let mut name = String::new();
            while j < cs.len() && cs[j] != '}' {
                name.push(cs[j]);
                j += 1;
            }
            if j >= cs.len() {
                out.push('$');
                i += 1;
                continue;
            }
            out.push_str(&lookup(&name, env));
            i = j + 1;
            continue;
        }
        let mut j = i + 1;
        let mut name = String::new();
        while j < cs.len() && (cs[j].is_ascii_alphanumeric() || cs[j] == '_') {
            name.push(cs[j]);
            j += 1;
        }
        if name.is_empty() {
            out.push('$');
            i += 1;
            continue;
        }
        out.push_str(&lookup(&name, env));
        i = j;
    }
    out
}

fn lookup(name: &str, env: &BTreeMap<String, String>) -> String {
    env.get(name).cloned().unwrap_or_default()
}

/// 展开一个词：变量 + 波浪号 + glob。返回 1..n 个结果。
fn expand_word(w: &Word, env: &BTreeMap<String, String>, last: i32, root: &Path, cwd: &str) -> Vec<String> {
    // 关键：lexer 把裸词拆成"每字符一个 Q::Bare 段"，所以不能逐段 expand_vars ——
    // 那样 `$FOO` 会被拆成 "$" "F" "O" "O" 四段，`$` 单独看没有变量名，原样留下，
    // 真机表现就是 `echo $FOO` 打出字面量 `$FOO`。必须先拼接连续的 Bare 原文，
    // 再整体做一次变量展开。
    let mut text = String::new();
    let mut bare = String::new();
    let mut globbable = !w.segs.is_empty();
    macro_rules! flush_bare {
        () => {
            if !bare.is_empty() {
                text.push_str(&expand_vars(&bare, env, last));
                bare.clear();
            }
        };
    }
    for seg in &w.segs {
        match seg.q {
            Q::Bare => bare.push_str(&seg.text),
            Q::Literal => {
                flush_bare!();
                globbable = false;
                text.push_str(&seg.text);
            }
            Q::Expand => {
                flush_bare!();
                globbable = false;
                text.push_str(&expand_vars(&seg.text, env, last));
            }
        }
    }
    flush_bare!();
    // 波浪号展开（只在裸词且位于词首）
    if let Some(rest) = text.strip_prefix('~') {
        if rest.is_empty() || rest.starts_with('/') {
            let home = env.get("HOME").cloned().unwrap_or_else(|| "/root".into());
            text = format!("{home}{rest}");
        }
    }
    if !globbable || !text.chars().any(|c| matches!(c, '*' | '?' | '[')) {
        return vec![text];
    }
    let g = glob_expand(&text, root, cwd);
    if g.is_empty() {
        vec![text]
    } else {
        g
    }
}

/// glob：在 jail 内枚举匹配。返回值保持调用方视角（相对则相对）。
fn glob_expand(pat: &str, root: &Path, cwd: &str) -> Vec<String> {
    let abs = pat.starts_with('/');
    let mut cur: Vec<String> = vec![String::new()];
    let parts: Vec<&str> = pat.split('/').filter(|s| !s.is_empty() && *s != ".").collect();
    let mut first = true;
    for part in &parts {
        let mut next: Vec<String> = Vec::new();
        let has_magic = part.chars().any(|c| matches!(c, '*' | '?' | '['));
        for base in &cur {
            let vdir = if first && !abs {
                // 相对路径：基于会话 cwd
                if base.is_empty() {
                    cwd.to_string()
                } else {
                    format!("{}/{}", cwd.trim_end_matches('/'), base)
                }
            } else if base.is_empty() {
                "/".to_string()
            } else {
                format!("/{}", base)
            };
            let hdir = crate::virt_to_host(root, &vdir);
            if !has_magic {
                let cand_v = if vdir == "/" { format!("/{part}") } else { format!("{}/{part}", vdir.trim_end_matches('/')) };
                next.push(cand_v.trim_start_matches('/').to_string());
                continue;
            }
            if let Ok(rd) = std::fs::read_dir(&hdir) {
                let mut names: Vec<String> = rd
                    .filter_map(|e| e.ok())
                    .map(|e| e.file_name().to_string_lossy().to_string())
                    .collect();
                names.sort();
                for n in names {
                    if n.starts_with('.') && !part.starts_with('.') {
                        continue;
                    }
                    if glob_match(part, &n) {
                        let v = if vdir == "/" { format!("/{n}") } else { format!("{}/{n}", vdir.trim_end_matches('/')) };
                        next.push(v.trim_start_matches('/').to_string());
                    }
                }
            }
        }
        cur = next;
        first = false;
        if cur.is_empty() {
            return Vec::new();
        }
    }
    let c = cwd.trim_end_matches('/').to_string();
    cur.into_iter()
        .map(|v| {
            let full = format!("/{v}");
            if abs {
                full
            } else if !c.is_empty() {
                match full.strip_prefix(&format!("{c}/")) {
                    Some(rest) => rest.to_string(),
                    None => full,
                }
            } else {
                full.trim_start_matches('/').to_string()
            }
        })
        .collect()
}

fn glob_match(pat: &str, name: &str) -> bool {
    let p: Vec<char> = pat.chars().collect();
    let n: Vec<char> = name.chars().collect();
    fn go(p: &[char], n: &[char]) -> bool {
        if p.is_empty() {
            return n.is_empty();
        }
        match p[0] {
            '*' => {
                let mut k = 0;
                loop {
                    if go(&p[1..], &n[k..]) {
                        return true;
                    }
                    if k >= n.len() {
                        return false;
                    }
                    k += 1;
                }
            }
            '?' => !n.is_empty() && go(&p[1..], &n[1..]),
            '[' => {
                if n.is_empty() {
                    return false;
                }
                let mut k = 1;
                let neg = p.get(1) == Some(&'!') || p.get(1) == Some(&'^');
                if neg {
                    k = 2;
                }
                let mut matched = false;
                let mut closed = false;
                while k < p.len() {
                    if p[k] == ']' && k > 1 {
                        closed = true;
                        break;
                    }
                    if k + 2 < p.len() && p[k + 1] == '-' {
                        if n[0] >= p[k] && n[0] <= p[k + 2] {
                            matched = true;
                        }
                        k += 3;
                    } else {
                        if p[k] == n[0] {
                            matched = true;
                        }
                        k += 1;
                    }
                }
                if !closed {
                    return !n.is_empty() && n[0] == '[' && go(&p[1..], &n[1..]);
                }
                if matched != neg {
                    go(&p[k + 1..], &n[1..])
                } else {
                    false
                }
            }
            c => !n.is_empty() && n[0] == c && go(&p[1..], &n[1..]),
        }
    }
    go(&p, &n)
}

#[derive(Default)]
struct Pipeline {
    cmds: Vec<CmdSpec>,
}

struct Job {
    pipelines: Vec<Pipeline>,
    seps: Vec<Op>,
}

fn parse(toks: &[Tok], s: &Session) -> Result<Job, String> {
    let env: BTreeMap<String, String> = s.env.lock().unwrap().clone();
    let last = s.last_status.load(Ordering::Relaxed);
    let root = s.root.clone();
    let cwd = s.cwd();

    let mut job = Job { pipelines: Vec::new(), seps: Vec::new() };
    let mut cmd = CmdSpec::default();
    let mut pipe: Pipeline = Pipeline { cmds: Vec::new() };
    let mut pending: Option<Op> = None; // 等待文件名的重定向
    let mut i = 0usize;

    let finish_cmd = |cmd: &mut CmdSpec, pipe: &mut Pipeline| {
        if !cmd.argv.is_empty() {
            pipe.cmds.push(std::mem::take(cmd));
        }
    };
    let finish_pipe = |pipe: &mut Pipeline, job: &mut Job, sep: Option<Op>| {
        if !pipe.cmds.is_empty() {
            job.pipelines.push(std::mem::take(pipe));
            if let Some(sp) = sep {
                job.seps.push(sp);
            }
        }
    };

    while i < toks.len() {
        match &toks[i] {
            Tok::W(w) => {
                let words = expand_word(w, &env, last, &root, &cwd);
                if let Some(op) = pending.take() {
                    let target = words.join(" ");
                    match op {
                        Op::Out => cmd.out = Some((target, false)),
                        Op::OutAppend => cmd.out = Some((target, true)),
                        Op::In => cmd.inp = Some(target),
                        Op::ErrOut => cmd.errf = Some((target, false)),
                        Op::ErrAppend => cmd.errf = Some((target, true)),
                        Op::Both => cmd.out = Some((target.clone(), false)),
                        Op::BothAppend => cmd.out = Some((target, true)),
                        _ => {}
                    }
                    if matches!(op, Op::Both | Op::BothAppend) {
                        cmd.merge = true;
                    }
                } else {
                    cmd.argv.extend(words);
                }
                i += 1;
            }
            Tok::O(op) => match op {
                Op::Pipe => {
                    finish_cmd(&mut cmd, &mut pipe);
                    i += 1;
                }
                Op::Seq | Op::And | Op::Or | Op::Background => {
                    finish_cmd(&mut cmd, &mut pipe);
                    finish_pipe(&mut pipe, &mut job, Some(*op));
                    i += 1;
                }
                Op::MergeErr => {
                    cmd.merge = true;
                    i += 1;
                }
                Op::Out | Op::OutAppend | Op::In | Op::ErrOut | Op::ErrAppend | Op::Both | Op::BothAppend => {
                    pending = Some(*op);
                    i += 1;
                }
            },
        }
    }
    finish_cmd(&mut cmd, &mut pipe);
    finish_pipe(&mut pipe, &mut job, None);
    Ok(job)
}

fn write_redirect(s: &Session, path: &str, data: &[u8], append: bool) -> Result<(), String> {
    let host = s.host_path(path);
    if let Some(p) = host.parent() {
        let _ = std::fs::create_dir_all(p);
    }
    let mut f = std::fs::OpenOptions::new()
        .write(true)
        .create(true)
        .append(append)
        .truncate(!append)
        .open(&host)
        .map_err(|e| format!("{}: {e}", path))?;
    use std::io::Write;
    f.write_all(data).map_err(|e| format!("{}: {e}", path))
}

fn emit(shared: &SharedOut, data: &[u8]) {
    if data.is_empty() {
        return;
    }
    let mut g = shared.lock().unwrap();
    g.extend_from_slice(data);
    if g.len() > 512 * 1024 {
        let cut = g.len() - 384 * 1024;
        let cut = cut - cut % 16;
        g.drain(..cut);
    }
}

/// 内建命令（不是 busybox applet，必须在宿主侧改会话状态）。
/// 返回 Some(code) 表示已处理。
fn try_builtin(s: &Session, c: &CmdSpec, stdin: &[u8], last_in_chain: bool) -> Option<(i32, Vec<u8>, Vec<u8>)> {
    let name = c.argv.first()?.as_str();
    let args = &c.argv[1..];
    match name {
        "cd" => {
            let target = args.first().cloned().unwrap_or_else(|| {
                s.env.lock().unwrap().get("HOME").cloned().unwrap_or_else(|| "/root".into())
            });
            let virt = s.virt_path(&target);
            let host = crate::virt_to_host(&s.root, &virt);
            match std::fs::metadata(&host) {
                Ok(m) if m.is_dir() => {
                    s.set_cwd(virt);
                    Some((0, Vec::new(), Vec::new()))
                }
                Ok(_) => Some((1, Vec::new(), format!("cd: {target}: Not a directory\n").into_bytes())),
                Err(e) => Some((1, Vec::new(), format!("cd: {target}: {e}\n").into_bytes())),
            }
        }
        "pwd" => {
            let mut o = s.cwd().into_bytes();
            o.push(b'\n');
            Some((0, o, Vec::new()))
        }
        "export" => {
            let err = Vec::new();
            if args.is_empty() {
                let mut o = Vec::new();
                for (k, v) in s.env.lock().unwrap().iter() {
                    o.extend_from_slice(format!("export {k}='{v}'\n").as_bytes());
                }
                return Some((0, o, err));
            }
            for a in args {
                match a.split_once('=') {
                    Some((k, v)) => {
                        s.env.lock().unwrap().insert(k.to_string(), v.to_string());
                    }
                    None => {
                        if !s.env.lock().unwrap().contains_key(a.as_str()) {
                            s.env.lock().unwrap().insert(a.clone(), String::new());
                        }
                    }
                }
            }
            Some((0, Vec::new(), err))
        }
        "unset" => {
            for a in args {
                s.env.lock().unwrap().remove(a.as_str());
            }
            Some((0, Vec::new(), Vec::new()))
        }
        "exit" => Some((args.first().and_then(|x| x.parse().ok()).unwrap_or(0), Vec::new(), Vec::new())),
        "true" | ":" => Some((0, Vec::new(), Vec::new())),
        "false" => Some((1, Vec::new(), Vec::new())),
        "echo" => {
            // 直接实现，避免每次 echo 都付一次引擎启动成本（脚本里 echo 最多）
            let mut no_nl = false;
            let mut esc = false;
            let mut out = Vec::new();
            let mut idx = 0;
            while idx < args.len() {
                let a = args[idx].as_str();
                if a == "-n" {
                    no_nl = true;
                } else if a == "-e" {
                    esc = true;
                } else if a == "-ne" || a == "-en" {
                    no_nl = true;
                    esc = true;
                } else {
                    break;
                }
                idx += 1;
            }
            let _ = stdin;
            for (k, a) in args[idx..].iter().enumerate() {
                if k > 0 {
                    out.push(b' ');
                }
                if esc {
                    let mut it = a.chars();
                    while let Some(ch) = it.next() {
                        if ch == '\\' {
                            match it.next() {
                                Some('n') => out.push(b'\n'),
                                Some('t') => out.push(b'\t'),
                                Some('r') => out.push(b'\r'),
                                Some('\\') => out.push(b'\\'),
                                Some('0') => out.push(0),
                                Some(other) => {
                                    out.push(b'\\');
                                    let mut b = [0u8; 4];
                                    out.extend_from_slice(other.encode_utf8(&mut b).as_bytes());
                                }
                                None => out.push(b'\\'),
                            }
                        } else {
                            let mut b = [0u8; 4];
                            out.extend_from_slice(ch.encode_utf8(&mut b).as_bytes());
                        }
                    }
                } else {
                    out.extend_from_slice(a.as_bytes());
                }
            }
            if !no_nl {
                out.push(b'\n');
            }
            let _ = last_in_chain;
            Some((0, out, Vec::new()))
        }
        _ => None,
    }
}

fn exec_pipeline(s: &Session, pl: &Pipeline, shared: &SharedOut) -> i32 {
    let mut stdin: Vec<u8> = Vec::new();
    let n = pl.cmds.len();
    let mut status = 0i32;
    let cwd = s.cwd();

    for (i, c) in pl.cmds.iter().enumerate() {
        let last = i + 1 == n;
        if c.argv.is_empty() {
            continue;
        }
        // 内建命令：只在这一段没有输入重定向时处理（有 `<` 时交给 busybox 不合语义，简化忽略）
        if let Some((code, mut o, mut e)) = try_builtin(s, c, &stdin, last) {
            if c.merge {
                o.extend_from_slice(&e);
                e.clear();
            }
            if let Some((path, append)) = &c.out {
                if let Err(msg) = write_redirect(s, path, &o, *append) {
                    e.extend_from_slice(format!("ch: {msg}\n").as_bytes());
                }
                o.clear();
            }
            if let Some((path, append)) = &c.errf {
                let _ = write_redirect(s, path, &e, *append);
                e.clear();
            }
            if last {
                emit(shared, &o);
                emit(shared, &e);
            } else {
                stdin = o;
                emit(shared, &e);
            }
            status = code;
            continue;
        }

        // 输入：`<` 文件优先，否则管道上游
        let input = match &c.inp {
            Some(p) => {
                let host = s.host_path(p);
                std::fs::read(&host).unwrap_or_else(|err| format!("ch: {p}: {err}\n").into_bytes())
            }
            None => stdin.clone(),
        };

        let mut argv: Vec<String> = vec!["busybox".to_string()];
        argv.extend(c.argv.iter().cloned());
        let r = s.run_capture(&argv, &input, &cwd);
        let mut out = r.out;
        let mut err = r.err;
        if c.merge {
            out.extend_from_slice(&err);
            err.clear();
        }
        if let Some((path, append)) = &c.out {
            if let Err(msg) = write_redirect(s, path, &out, *append) {
                err.extend_from_slice(format!("ch: {msg}\n").as_bytes());
            }
            out.clear();
        }
        if let Some((path, append)) = &c.errf {
            let _ = write_redirect(s, path, &err, *append);
            err.clear();
        }
        if r.truncated {
            err.extend_from_slice(
                "[ch: 该段输出超过 24MB，上游已按 SIGPIPE 结束]
".as_bytes(),
            );
        }
        status = r.code;
        if last {
            emit(shared, &out);
            emit(shared, &err);
        } else {
            stdin = out;
            emit(shared, &err);
        }
    }
    status
}

/// 执行一行（可能是 `a; b && c || d`）。返回最后一个条件的退出码。
pub fn run_line(s: &Session, line: &str, shared: &SharedOut) -> i32 {
    let trimmed = line.trim();
    if trimmed.is_empty() {
        return 0;
    }
    return run_script(s, line, shared);
    #[allow(unreachable_code)]
    // 字面量 "exit" 不结束会话（终端保持可用），只把状态清零
    let toks = match lex(line) {
        Ok(t) => t,
        Err(e) => {
            emit(shared, format!("ch: {e}\n").as_bytes());
            s.last_status.store(2, Ordering::Relaxed);
            return 2;
        }
    };
    if toks.is_empty() {
        return 0;
    }
    let job = match parse(&toks, s) {
        Ok(j) => j,
        Err(e) => {
            emit(shared, format!("ch: {e}\n").as_bytes());
            s.last_status.store(2, Ordering::Relaxed);
            return 2;
        }
    };
    let mut status = s.last_status.load(Ordering::Relaxed);
    for (idx, pl) in job.pipelines.iter().enumerate() {
        let run_it = match job.seps.get(idx.wrapping_sub(1)) {
            Some(Op::And) if idx > 0 => status == 0,
            Some(Op::Or) if idx > 0 => status != 0,
            _ => true,
        };
        if !run_it {
            continue;
        }
        status = exec_pipeline(s, pl, shared);
    }
    s.last_status.store(status, Ordering::Relaxed);
    status
}

/// 顶层分隔符切分：`;` `&&` `||` `&`。
/// 必须在**执行前**切分，而不是一次 parse 整行 —— parse 会把 env 与 cwd 快照下来，
/// 于是 `export A=1; echo $A` 里 $A 展开不到、`cd /tmp; ls *.txt` 会用旧 cwd 做
/// glob（真机表现：变量与通配符"时灵时不灵"）。逐段 parse 才能让每条命令看到
/// 上一条命令留下的 env / cwd。
pub fn run_script(s: &Session, line: &str, shared: &SharedOut) -> i32 {
    let toks = match lex(line) {
        Ok(t) => t,
        Err(e) => {
            emit(shared, format!("ch: {e}
").as_bytes());
            s.last_status.store(2, Ordering::Relaxed);
            return 2;
        }
    };
    // 切成 [(段内 token, 段前的分隔符)]
    let mut chunks: Vec<(Vec<Tok>, Option<Op>)> = Vec::new();
    let mut cur: Vec<Tok> = Vec::new();
    let mut pend: Option<Op> = None;
    for t in toks {
        match t {
            Tok::O(op) if matches!(op, Op::Seq | Op::And | Op::Or | Op::Background) => {
                if !cur.is_empty() {
                    chunks.push((std::mem::take(&mut cur), pend.take()));
                }
                pend = Some(op);
            }
            other => cur.push(other),
        }
    }
    if !cur.is_empty() {
        chunks.push((cur, pend.take()));
    }

    let mut status = s.last_status.load(Ordering::Relaxed);
    for (i, (cts, sep)) in chunks.iter().enumerate() {
        if i > 0 {
            match sep {
                Some(Op::And) if status != 0 => continue,
                Some(Op::Or) if status == 0 => continue,
                _ => {}
            }
        }
        let job = match parse(cts, s) {
            Ok(j) => j,
            Err(e) => {
                emit(shared, format!("ch: {e}
").as_bytes());
                status = 2;
                continue;
            }
        };
        for pl in job.pipelines.iter() {
            status = exec_pipeline(s, pl, shared);
        }
        // 逐段刷新 $?：否则 `false; echo $?` 里 parse 快照到的还是整行执行前的状态
        s.last_status.store(status, Ordering::Relaxed);
    }
    s.last_status.store(status, Ordering::Relaxed);
    status
}
