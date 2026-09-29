# -*- coding: utf-8 -*-
# 通过 api.github.com 的 Git Data API 把本地 commit 精确重放到远端（github.com 被墙时的推送方案）
import base64, json, re, subprocess, sys, urllib.request, urllib.error

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
REPO_DIR = r'C:\Users\Administrator\Documents\Default Project\ledger'
FULL = 'chenzhongyu331166-hub/ledger'
LOCAL_COMMIT = subprocess.run(['git', '-C', REPO_DIR, 'rev-parse', 'master'],
                              capture_output=True, text=True).stdout.strip()
print('pushing local commit', LOCAL_COMMIT)


def git(*args, binary=False):
    out = subprocess.run(['git', '-C', REPO_DIR] + list(args),
                         capture_output=True, timeout=60)
    if out.returncode != 0:
        raise RuntimeError('git failed: %s\n%s' % (args, out.stderr.decode('utf-8', 'replace')))
    return out.stdout if binary else out.stdout.decode('utf-8')


def api(path, body=None, method=None):
    b = open(r'C:\Users\Administrator\.git-credentials', 'rb').read()
    tok = re.search(rb'https://[^:]+:([^@]+)@github\.com', b).group(1).decode()
    data = json.dumps(body).encode('utf-8') if body is not None else None
    last = None
    for attempt in range(8):
        req = urllib.request.Request('https://api.github.com' + path, data=data,
                                     headers={'Authorization': 'Bearer ' + tok,
                                              'Accept': 'application/vnd.github+json',
                                              'User-Agent': 'opencode'},
                                     method=method or ('POST' if body is not None else 'GET'))
        try:
            r = urllib.request.urlopen(req, timeout=40)
            return json.load(r)
        except urllib.error.HTTPError as e:
            raise RuntimeError('API %s -> %s %s' % (path, e.code, e.read().decode('utf-8', 'replace')[:400]))
        except Exception as e:
            last = e
            print('  retry %d (%s): %s' % (attempt + 1, path, e))
            __import__('time').sleep(4 + attempt * 3)
    raise RuntimeError('API failed after retries: %s %s' % (path, last))


# ---- 1. 解析本地 commit ----
raw = git('cat-file', 'commit', LOCAL_COMMIT, binary=True)
head, msg = raw.split(b'\n\n', 1)
lines = head.decode('utf-8').split('\n')
tree_sha = parents = None
author = committer = None
for ln in lines:
    if ln.startswith('tree '):
        tree_sha = ln[5:]
    elif ln.startswith('parent '):
        parents = ln[7:]
    elif ln.startswith('author '):
        author = ln[7:]
    elif ln.startswith('committer '):
        committer = ln[10:]
print('local commit tree=%s parent=%s' % (tree_sha, parents))


def parse_ident(s):
    # Name <email> <ts> +0800
    m = re.match(r'^(.*?) <([^>]+)> (\d+) ([+-]\d{4})$', s)
    name, email, ts, off = m.groups()
    sign = '+' if off[0] == '+' else '-'
    import datetime as dt
    utc = dt.datetime.utcfromtimestamp(int(ts))
    off_h, off_m = int(off[1:3]), int(off[3:5])
    local = utc + dt.timedelta(hours=off_h if sign == '+' else -off_h,
                               minutes=off_m if sign == '+' else -off_m)
    iso = local.strftime('%Y-%m-%dT%H:%M:%S') + sign + '%02d:%02d' % (off_h, off_m)
    return {'name': name, 'email': email, 'date': iso}, int(ts)


author_d, author_ts = parse_ident(author)
committer_d, committer_ts = parse_ident(committer)
print('author:', author_d)

# ---- 2. 远端状态 ----
ref = api('/repos/%s/git/ref/heads/master' % FULL)
remote_sha = ref['object']['sha']
print('remote master:', remote_sha)
if remote_sha == LOCAL_COMMIT:
    print('already up to date')
    sys.exit(0)

# ---- 3. 哪些文件变了 ----
changed = git('diff', '--name-only', remote_sha, LOCAL_COMMIT).split()
print('changed:', changed)

# ---- 4. 上传新 blob ----
def blob_sha(path):
    return git('rev-parse', '%s:%s' % (LOCAL_COMMIT, path)).strip()


for p in changed:
    mode_type = git('ls-tree', LOCAL_COMMIT, '--', p).split()[0:2]  # mode, type
    content = git('show', '%s:%s' % (LOCAL_COMMIT, p), binary=True)
    r = api('/repos/%s/git/blobs' % FULL,
            {'content': base64.b64encode(content).decode(), 'encoding': 'base64'})
    local = blob_sha(p)
    if r['sha'] != local:
        raise RuntimeError('blob sha mismatch for %s: api=%s local=%s' % (p, r['sha'], local))
    print('blob ok', p, local[:8])

# ---- 5. 重建受影响的 tree（自底向上，全量条目，不依赖 base_tree）----
def ls_tree(tree_ish, path=''):
    spec = tree_ish + (':' + path if path else '')
    out = git('ls-tree', spec)
    entries = []
    for ln in out.splitlines():
        meta, name = ln.split('\t', 1)
        mode, typ, sha = meta.split()
        entries.append({'path': name, 'mode': mode, 'type': typ, 'sha': sha})
    return entries


# 顶层哪些子树要重建：app.py(文件)、static/、mobile/
top = ls_tree(LOCAL_COMMIT)
changed_dirs = set()
for p in changed:
    if '/' in p:
        changed_dirs.add(p.split('/')[0])
    else:
        pass

new_top = []
for e in top:
    if e['type'] == 'tree' and e['path'] in changed_dirs:
        # 重建该子树（可能还有更深的子树层）
        sub_entries = ls_tree(LOCAL_COMMIT, e['path'])
        rebuilt = []
        for se in sub_entries:
            if se['type'] == 'tree':
                # 深层子树：changed 路径若穿透则重建，否则直接引用（对象已在仓库/远端）
                prefix = e['path'] + '/' + se['path']
                if any(c.startswith(prefix + '/') for c in changed):
                    deep = ls_tree(LOCAL_COMMIT, prefix)
                    r = api('/repos/%s/git/trees' % FULL, {'tree': deep})
                    local_tree = git('rev-parse', '%s:%s' % (LOCAL_COMMIT, prefix)).strip()
                    if r['sha'] != local_tree:
                        raise RuntimeError('tree sha mismatch %s api=%s local=%s' % (prefix, r['sha'], local_tree))
                    se = dict(se, sha=r['sha'])
                    print('tree ok', prefix, local_tree[:8])
                rebuilt.append(se)
            else:
                rebuilt.append(se)
        r = api('/repos/%s/git/trees' % FULL, {'tree': rebuilt})
        if r['sha'] != e['sha']:
            raise RuntimeError('tree sha mismatch %s api=%s local=%s' % (e['path'], r['sha'], e['sha']))
        print('tree ok', e['path'], e['sha'][:8])
        rebuilt = dict(e, sha=r['sha'])
        e = rebuilt
    new_top.append(e)

r = api('/repos/%s/git/trees' % FULL, {'tree': new_top})
if r['sha'] != tree_sha:
    raise RuntimeError('root tree mismatch api=%s local=%s' % (r['sha'], tree_sha))
print('root tree ok', tree_sha[:8])

# ---- 6. 重放 commit ----
message = msg.decode('utf-8')
commit_body = {'message': message, 'tree': tree_sha, 'parents': [parents],
               'author': author_d, 'committer': committer_d}
r = api('/repos/%s/git/commits' % FULL, commit_body)
print('api commit sha:', r['sha'], '| local:', LOCAL_COMMIT)
if r['sha'] != LOCAL_COMMIT:
    # message 尾换行等差异 → 用 API 返回值在本地重建对象，保证两边一致
    print('WARN commit sha mismatch, reconstructing locally...')
    gc = api('/repos/%s/git/commits/%s' % (FULL, r['sha']))
    import email.utils
    def ident(d, ts):
        # Name <email> <ts> +0800 —— 用原始 local ts 与相同 offset 还原
        return '%s <%s> %d +0800' % (d['name'], d['email'], ts)
    a_line = ident(gc['author'], author_ts)
    c_line = ident(gc['committer'], committer_ts)
    raw2 = ('tree %s\nparent %s\nauthor %s\ncommitter %s\n\n' %
            (gc['tree'], gc['parents'][0], a_line, c_line)).encode('utf-8') + gc['message'].encode('utf-8')
    sha2 = subprocess.run(['git', '-C', REPO_DIR, 'hash-object', '-t', 'commit', '-w', '--stdin'],
                          input=raw2, capture_output=True).stdout.decode().strip()
    print('reconstructed local object:', sha2)
    if sha2 != r['sha']:
        # 还不一致：放弃精确匹配，直接把本地 master 指到 API sha（内容一致即可）
        print('reconstruct mismatch too; will point local ref to api sha')
    target = r['sha']
else:
    target = r['sha']

# ---- 7. 更新远端 ref ----
r = api('/repos/%s/git/refs/heads/master' % FULL, {'sha': target}, method='PATCH')
print('ref updated ->', r['object']['sha'])

# ---- 8. 同步本地 ----
if target != LOCAL_COMMIT:
    subprocess.run(['git', '-C', REPO_DIR, 'update-ref', 'refs/heads/master', target], check=True)
    print('local master moved to', target)
else:
    print('local and remote match:', target)
print('DONE')
