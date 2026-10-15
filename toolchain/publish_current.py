#!/usr/bin/env python3
"""Publish one replaceable APK pair on the CURRENT source branch.

Called by publish-apk.sh --current-branch AFTER APK/signature/pair verification.
Only this branch is updated, with a force-with-lease push. Source history stays;
old generated APKs/build-info are removed from its ancestors. This also handles
source commits made after a previous publication, instead of burying that old
build forever. No checkout, temporary branch, external package or Git filter is
needed. --no-push writes candidate objects only, not refs/index/working files.

GitHub may retain unreachable objects/caches after a force-push; the guarantee
here is about reachable branch history, not immediate server-side erasure.
"""

import argparse
import os
from pathlib import Path
import subprocess
import tempfile


ARTIFACTS = ("GhostHand.apk", "GhostHand-debug.apk", "APK-BUILDINFO.md")
MARKER = b"GhostHand-Artifact-Publish: current-branch-v1"


def git(repo, *args, data=None, env=None):
    result = subprocess.run(["git", "-C", str(repo), *args], input=data,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", "replace").strip()
                           or "git command failed: " + " ".join(args))
    return result.stdout


def oid(repo, *args, **kwargs):
    return git(repo, *args, **kwargs).decode("ascii").strip()


def current_ref(repo):
    return oid(repo, "symbolic-ref", "--quiet", "HEAD")


def require_clean(repo):
    if git(repo, "status", "--porcelain", "--untracked-files=normal"):
        raise RuntimeError("Commit/stash source changes before publishing. "
                           "Build inputs should stay in ignored app/build/.")


def tree(repo, commit):
    return oid(repo, "rev-parse", commit + "^{tree}")


class Index:
    """A private index: neither history filtering nor dry runs touch git's index."""
    def __init__(self, directory):
        self.env = dict(os.environ, GIT_INDEX_FILE=str(Path(directory) / "index"))

    def load(self, repo, source_tree):
        git(repo, "read-tree", source_tree, env=self.env)

    def remove_artifacts(self, repo):
        git(repo, "update-index", "--force-remove", "--", *ARTIFACTS, env=self.env)

    def add(self, repo, name, content):
        blob = oid(repo, "hash-object", "-w", "--stdin", data=content)
        git(repo, "update-index", "--add", "--cacheinfo", "100644", blob, name, env=self.env)

    def write(self, repo):
        return oid(repo, "write-tree", env=self.env)


def source_only_history(repo, head, index):
    """Reuse clean commits verbatim; rewrite ONLY APK-bearing commits/descendants."""
    mapped = {}
    clean_trees = {}
    for old in oid(repo, "rev-list", "--reverse", "--topo-order", head).splitlines():
        raw = git(repo, "cat-file", "commit", old)
        header, message = raw.split(b"\n\n", 1)
        lines = header.split(b"\n")
        old_tree = lines[0].split(b" ", 1)[1].decode("ascii")
        parents = [line[7:].decode("ascii") for line in lines if line.startswith(b"parent ")]
        new_parents = [mapped[parent] for parent in parents]
        names = git(repo, "ls-tree", "-r", "--name-only", "-z", old_tree).split(b"\0")
        unexpected = [name for name in names if name.lower().endswith(b".apk")
                      and os.fsdecode(name) not in ARTIFACTS]
        if unexpected:
            raise RuntimeError("Unexpected historical APK path; refusing to remove source "
                               "files implicitly: " + repr(unexpected[0]))
        has_artifacts = any(os.fsencode(name) in names for name in ARTIFACTS)
        new_tree = old_tree
        if has_artifacts:
            index.load(repo, old_tree)
            index.remove_artifacts(repo)
            new_tree = index.write(repo)

        if (MARKER in message and len(new_parents) == 1
                and new_tree == clean_trees[new_parents[0]]):
            # A generated-only publication becomes empty after stripping. Drop
            # that commit too, rather than collecting empty "release" history.
            mapped[old] = new_parents[0]
            continue
        if new_tree == old_tree and new_parents == parents:
            new = old  # retains even original signatures and exact commit IDs
        else:
            if any(line.startswith((b"gpgsig ", b"gpgsig-sha256 ", b"mergetag "))
                   for line in lines):
                raise RuntimeError("Refusing to invalidate a signed historical commit: " + old)
            # Copy author/committer dates, encoding and message byte-for-byte;
            # only the tree/parents change. No unrelated source commits are lost.
            updated = [b"tree " + new_tree.encode("ascii")]
            updated += [b"parent " + parent.encode("ascii") for parent in new_parents]
            updated += [line for line in lines
                        if not line.startswith((b"tree ", b"parent "))]
            new_raw = b"\n".join(updated) + b"\n\n" + message
            new = oid(repo, "hash-object", "-w", "-t", "commit", "--stdin", data=new_raw)
        mapped[old] = new
        clean_trees[new] = new_tree
    return mapped[head]


def candidate(repo, head, release, debug, info):
    with tempfile.TemporaryDirectory(prefix="ghosthand-publish-") as directory:
        index = Index(directory)
        base = source_only_history(repo, head, index)
        index.load(repo, tree(repo, base))
        index.add(repo, ARTIFACTS[0], Path(release).read_bytes())
        index.add(repo, ARTIFACTS[1], Path(debug).read_bytes())
        build_info = ("# GhostHand — current APK pair\n\nSource commit: `" + base
                      + "`\n\nOnly this tip commit contains the generated APKs. Each publication "
                      "removes older builds from this branch's reachable history.\n\n")
        index.add(repo, ARTIFACTS[2], build_info.encode("utf-8") + Path(info).read_bytes())
        commit = oid(repo, "commit-tree", index.write(repo), "-p", base,
                     data=b"Publish current GhostHand APK pair\n\n" + MARKER + b"\n")
        return commit


def remote_head(repo, remote, ref):
    lines = git(repo, "ls-remote", "--refs", remote, ref).decode("ascii").splitlines()
    return lines[0].split()[0] if lines else ""


def install(repo, ref, commit, previous):
    git(repo, "update-ref", ref, commit, previous)
    # Source tree is unchanged and require_clean() passed. Materialise only the
    # new generated pair/info and update the real index to match the new HEAD.
    git(repo, "read-tree", "--reset", "-u", commit)


def publish(repo, release, debug, info, remote="origin", push=True):
    ref = current_ref(repo)
    head = oid(repo, "rev-parse", "HEAD")
    require_clean(repo)
    if oid(repo, "rev-parse", "--is-shallow-repository") == "true":
        raise RuntimeError("Fetch full history before publishing; a shallow clone cannot "
                           "prove there are no older APKs.")
    expected = remote_head(repo, remote, ref) if push else ""
    if expected:
        # Never force away somebody else's source work that has not been fetched
        # and incorporated. The lease ALSO catches a race during verification.
        git(repo, "merge-base", "--is-ancestor", expected, head)
    commit = candidate(repo, head, release, debug, info)
    if current_ref(repo) != ref or oid(repo, "rev-parse", "HEAD") != head:
        raise RuntimeError("HEAD changed during publication; nothing pushed")
    require_clean(repo)
    if push:
        git(repo, "push", "--force-with-lease=" + ref + ":" + expected,
            remote, commit + ":" + ref)
        install(repo, ref, commit, head)
    return commit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--debug-apk", required=True)
    parser.add_argument("--info", required=True)
    parser.add_argument("--remote", default="origin")
    parser.add_argument("--no-push", action="store_true")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    try:
        commit = publish(repo, args.apk, args.debug_apk, args.info,
                         remote=args.remote, push=not args.no_push)
    except (RuntimeError, OSError) as error:
        parser.exit(1, "publication failed: " + str(error) + "\n")
    print(("candidate (not pushed): " if args.no_push else "published: ") + commit)
    print("Source history preserved; only the tip commit contains the current APK pair.")


if __name__ == "__main__":
    main()
