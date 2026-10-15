"""Git-only integration tests; no APK build, network or additional packages needed.

python3 -m unittest discover -s toolchain -p 'test_publish_current.py' -v
"""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import publish_current as pub


BRANCH = "arena/01a0d380-ghosthand"
REF = "refs/heads/" + BRANCH


class PublishCurrentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ghosthand-publish-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "source"
        self.repo.mkdir()
        pub.git(self.repo, "init", "-q", "--initial-branch=" + BRANCH)
        pub.git(self.repo, "config", "user.name", "Publish Test")
        pub.git(self.repo, "config", "user.email", "test@example.invalid")
        pub.git(self.repo, "config", "commit.gpgSign", "false")
        self.readme = self.repo / "README.md"
        self.readme.write_text("source version one\n")
        (self.repo / ".gitignore").write_text("*.apk\napp/build/\n")
        pub.git(self.repo, "add", "README.md", ".gitignore")
        pub.git(self.repo, "commit", "-qm", "Original source")
        self.base = self.head()
        self.release = self.root / "release.apk"
        self.debug = self.root / "debug.apk"
        self.info = self.root / "info.md"
        self.release.write_bytes(b"release-one")
        self.debug.write_bytes(b"debug-one")
        self.info.write_text("Validated APK pair\n")

    def head(self):
        return pub.oid(self.repo, "rev-parse", "HEAD")

    def candidate(self):
        return pub.candidate(self.repo, self.head(), self.release, self.debug, self.info)

    def install(self, commit):
        pub.install(self.repo, REF, commit, self.head())

    def publish(self, **kwargs):
        return pub.publish(self.repo, self.release, self.debug, self.info, **kwargs)

    def assert_only_current_pair(self, tip):
        names = pub.git(self.repo, "ls-tree", "-r", "--name-only", tip).decode().splitlines()
        for name in pub.ARTIFACTS:
            self.assertIn(name, names)
        for ancestor in pub.oid(self.repo, "rev-list", tip + "^").splitlines():
            old_names = pub.git(self.repo, "ls-tree", "-r", "--name-only", ancestor).decode().splitlines()
            self.assertFalse(set(pub.ARTIFACTS).intersection(old_names), ancestor)
            self.assertFalse(any(name.endswith(".apk") for name in old_names), ancestor)

    def remote(self):
        remote = self.root / "remote.git"
        remote.mkdir()
        pub.git(remote, "init", "--bare", "-q", "--initial-branch=" + BRANCH)
        pub.git(self.repo, "remote", "add", "origin", str(remote))
        pub.git(self.repo, "push", "origin", BRANCH)
        return remote

    def other_source_commit(self):
        return pub.oid(self.repo, "commit-tree", pub.tree(self.repo, self.head()),
                       "-p", self.head(), data=b"Another publisher's source commit\n")

    def test_first_publication_keeps_source_history_and_one_apk_tip(self):
        tip = self.candidate()
        self.install(tip)
        self.assertEqual(self.base, pub.oid(self.repo, "rev-parse", "HEAD^"))
        self.assertEqual("source version one\n", self.readme.read_text())
        self.assertEqual(b"release-one", (self.repo / pub.ARTIFACTS[0]).read_bytes())
        self.assertEqual(b"debug-one", (self.repo / pub.ARTIFACTS[1]).read_bytes())
        self.assertEqual(b"", pub.git(self.repo, "status", "--porcelain"))
        self.assert_only_current_pair(tip)

    def test_republication_drops_previous_commit_and_old_binary_blobs(self):
        first = self.candidate()
        self.install(first)
        old_blob = pub.oid(self.repo, "rev-parse", first + ":GhostHand.apk")
        self.release.write_bytes(b"release-two")
        self.debug.write_bytes(b"debug-two")
        second = self.candidate()
        self.install(second)
        reachable = pub.git(self.repo, "rev-list", "--objects", second).decode()
        self.assertNotIn(first, reachable)
        self.assertNotIn(old_blob, reachable)
        self.assertEqual(self.base, pub.oid(self.repo, "rev-parse", second + "^"))
        self.assertEqual(b"release-two", (self.repo / pub.ARTIFACTS[0]).read_bytes())
        self.assert_only_current_pair(second)

    def test_source_edits_above_previous_publication_are_preserved(self):
        first = self.candidate()
        self.install(first)
        self.readme.write_text("source version two\n")
        pub.git(self.repo, "commit", "-qam", "Keep this source edit")
        source = self.head()
        old = pub.git(self.repo, "cat-file", "commit", source)
        second = self.candidate()
        self.install(second)
        rewritten_source = pub.oid(self.repo, "rev-parse", second + "^")
        rewritten = pub.git(self.repo, "cat-file", "commit", rewritten_source)
        for prefix in (b"author ", b"committer "):
            self.assertEqual([line for line in old.splitlines() if line.startswith(prefix)],
                             [line for line in rewritten.splitlines() if line.startswith(prefix)])
        self.assertEqual(old.split(b"\n\n", 1)[1], rewritten.split(b"\n\n", 1)[1])
        self.assertEqual("source version two\n", self.readme.read_text())
        self.assertEqual(self.base, pub.oid(self.repo, "rev-parse", rewritten_source + "^"))
        self.assertNotIn(first, pub.oid(self.repo, "rev-list", second).splitlines())
        self.assert_only_current_pair(second)

    def test_legitimate_empty_source_commits_are_not_discarded(self):
        pub.git(self.repo, "commit", "--allow-empty", "-qm", "Intentional source milestone")
        source = self.head()
        tip = self.candidate()
        self.assertEqual(source, pub.oid(self.repo, "rev-parse", tip + "^"))
        self.assert_only_current_pair(tip)

    def test_dirty_source_is_refused_and_not_modified(self):
        self.readme.write_text("uncommitted work\n")
        with self.assertRaisesRegex(RuntimeError, "source changes"):
            self.publish(push=False)
        self.assertEqual(self.base, self.head())
        self.assertEqual("uncommitted work\n", self.readme.read_text())

    def test_untracked_source_is_refused(self):
        (self.repo / "new-source.txt").write_text("not committed\n")
        with self.assertRaisesRegex(RuntimeError, "source changes"):
            self.publish(push=False)
        self.assertEqual(self.base, self.head())

    def test_no_push_does_not_change_refs_index_or_working_files(self):
        original_index = pub.oid(self.repo, "write-tree")
        tip = self.publish(push=False)
        self.assertEqual(self.base, self.head())
        self.assertEqual(original_index, pub.oid(self.repo, "write-tree"))
        self.assertFalse((self.repo / pub.ARTIFACTS[0]).exists())
        self.assertFalse((self.repo / pub.ARTIFACTS[2]).exists())
        self.assert_only_current_pair(tip)

    def test_shallow_history_is_refused_instead_of_claiming_a_full_audit(self):
        pub.git(self.repo, "commit", "--allow-empty", "-qm", "Second source commit")
        shallow = self.root / "shallow"
        pub.git(self.root, "clone", "-q", "--depth=1", "--branch", BRANCH,
                self.repo.as_uri(), str(shallow))
        with self.assertRaisesRegex(RuntimeError, "Fetch full history"):
            pub.publish(shallow, self.release, self.debug, self.info, push=False)
        self.assertEqual(self.head(), pub.oid(shallow, "rev-parse", "HEAD"))

    def test_push_and_repush_update_only_the_same_branch(self):
        remote = self.remote()
        first = self.publish()
        self.assertEqual(first, self.head())
        self.assertEqual(first, pub.oid(remote, "rev-parse", REF))
        self.release.write_bytes(b"new-release")
        second = self.publish()
        self.assertEqual(second, self.head())
        self.assertEqual(second, pub.oid(remote, "rev-parse", REF))
        self.assertEqual(REF, pub.oid(remote, "for-each-ref", "--format=%(refname)", "refs/heads/"))
        self.assertNotIn(first, pub.oid(remote, "rev-list", REF).splitlines())
        self.assert_only_current_pair(second)

    def test_unincorporated_remote_source_is_never_overwritten(self):
        remote = self.remote()
        other = self.other_source_commit()
        pub.git(self.repo, "push", "origin", other + ":" + REF)
        with self.assertRaises(RuntimeError):
            self.publish()
        self.assertEqual(other, pub.oid(remote, "rev-parse", REF))
        self.assertEqual(self.base, self.head())

    def test_lease_catches_a_push_between_preflight_and_publication(self):
        remote = self.remote()
        other = self.other_source_commit()
        real_candidate = pub.candidate

        def racing_candidate(*args):
            tip = real_candidate(*args)
            pub.git(self.repo, "push", "origin", other + ":" + REF)
            return tip

        with patch.object(pub, "candidate", side_effect=racing_candidate):
            with self.assertRaises(RuntimeError):
                self.publish()
        self.assertEqual(other, pub.oid(remote, "rev-parse", REF))
        self.assertEqual(self.base, self.head())
        self.assertFalse((self.repo / pub.ARTIFACTS[0]).exists())

    def test_unknown_historical_apks_are_not_implicitly_deleted(self):
        fixture = self.repo / "source-fixture.apk"
        fixture.write_bytes(b"deliberate test fixture")
        pub.git(self.repo, "add", "-f", fixture.name)
        pub.git(self.repo, "commit", "-qm", "A source fixture must not silently vanish")
        original = self.head()
        with self.assertRaisesRegex(RuntimeError, "Unexpected historical APK"):
            self.publish(push=False)
        self.assertEqual(original, self.head())
        self.assertEqual(b"deliberate test fixture", fixture.read_bytes())

    def test_signed_source_rewrites_are_refused_not_silently_invalidated(self):
        first = self.candidate()
        self.install(first)
        raw = pub.git(self.repo, "cat-file", "commit", first)
        header, _ = raw.split(b"\n\n", 1)
        lines = header.splitlines()
        header = b"\n".join([lines[0], b"parent " + first.encode()]
                             + [line for line in lines if not line.startswith((b"tree ", b"parent "))])
        raw_signed = (header
                      + b"\ngpgsig -----BEGIN PGP SIGNATURE-----\n test-only\n -----END PGP SIGNATURE-----"
                      + b"\n\nSigned source milestone\n")
        signed = pub.oid(self.repo, "hash-object", "-w", "-t", "commit", "--stdin", data=raw_signed)
        self.install(signed)
        with self.assertRaisesRegex(RuntimeError, "signed historical commit"):
            self.publish(push=False)
        self.assertEqual(signed, self.head())

    def test_merge_source_history_and_both_parents_are_preserved(self):
        first = self.candidate()
        self.install(first)
        with tempfile.TemporaryDirectory() as temporary:
            index = pub.Index(temporary)
            index.load(self.repo, pub.tree(self.repo, first))
            index.add(self.repo, "README.md", b"merged source\n")
            merged_tree = index.write(self.repo)
        left = pub.oid(self.repo, "commit-tree", merged_tree, "-p", first, data=b"Left source edit\n")
        right = pub.oid(self.repo, "commit-tree", pub.tree(self.repo, self.base),
                        "-p", self.base, data=b"Right source edit\n")
        merge = pub.oid(self.repo, "commit-tree", merged_tree, "-p", left, "-p", right,
                        data=b"Keep this source merge\n")
        self.install(merge)
        tip = self.candidate()
        self.install(tip)
        source = pub.oid(self.repo, "rev-parse", tip + "^")
        parents = pub.oid(self.repo, "show", "-s", "--format=%P", source).split()
        self.assertEqual(2, len(parents))
        self.assertIn(right, parents)  # clean source commit reused verbatim
        self.assertEqual("merged source\n", self.readme.read_text())
        self.assert_only_current_pair(tip)


if __name__ == "__main__":
    unittest.main()
