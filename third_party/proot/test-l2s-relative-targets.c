/*
 * Regression test for the link2symlink relative-target resolution fix
 * (Linexa patch 0002, audit finding F-1/F-2).
 *
 * Creates a --link2symlink hardlink family in the RELATIVE-target spelling
 * Linexa's L2sLinkRewriter (§1.168) leaves in installed rootfs trees:
 *
 *     .l2s.base0001.0003            (data file, the real bytes)
 *     .l2s.base0001  -> .l2s.base0001.0003     (sentinel, relative)
 *     base           -> .l2s.base0001          (members, relative)
 *     hardlink1      -> .l2s.base0001
 *     hardlink2      -> .l2s.base0001
 *
 * then exercises exactly the syscall set that failed with EPERM when PRoot
 * resolved those targets against its own process cwd:
 *
 *   - lstat() on a member          (dpkg tarobject's gate: the exact
 *                                    "unable to stat './usr/bin/unzip':
 *                                     Operation not permitted" signature)
 *   - lstat() on the sentinel      (ls -la / find / file)
 *   - stat() on a member (follow)  (must keep working — cat/execve path)
 *   - rename() over a member       (dpkg's .dpkg-new replace dance)
 *   - unlink() of members          (dpkg remove; family bookkeeping)
 *   - link() onto the family       (dpkg's hardlink member install)
 *
 * Build: gcc -o test-l2s-relative-targets test-l2s-relative-targets.c
 * Run INSIDE a proot session:
 *     proot --link2symlink -r <rootfs> ./test-l2s-relative-targets <dir>
 * Expected: exit 0 with "L2S-RELATIVE-TARGETS OK".
 * Before the fix: "FAIL lstat(member)" (errno 1, EPERM) and
 * "FAIL link(onto family)" (errno 1, EPERM).
 */
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static int failures = 0;

static void check(int ok, const char *what)
{
	if (ok) {
		printf("  ok   %s\n", what);
	} else {
		printf("  FAIL %s (errno=%d %s)\n", what, errno, strerror(errno));
		failures++;
	}
}

int main(int argc, char **argv)
{
	char dir[1024], base[1100], link1[1100], link2[1100];
	char sentinel[1100], data[1100], tmpname[1100], newname[1100];
	struct stat st;
	FILE *f;

	if (argc < 2) {
		fprintf(stderr, "usage: %s <writable-dir>\n", argv[0]);
		return 125;
	}
	snprintf(dir, sizeof dir, "%s/l2s-regression", argv[1]);
	snprintf(base, sizeof base, "%s/base", dir);
	snprintf(link1, sizeof link1, "%s/hardlink1", dir);
	snprintf(link2, sizeof link2, "%s/hardlink2", dir);
	snprintf(sentinel, sizeof sentinel, "%s/.l2s.base0001", dir);
	snprintf(data, sizeof data, "%s/.l2s.base0001.0003", dir);

	if (mkdir(dir, 0755) != 0 && errno != EEXIST) {
		perror("mkdir");
		return 1;
	}
	unlink(base); unlink(link1); unlink(link2);
	unlink(sentinel); unlink(data);

	/* 1. The §1.168 post-install family shape, relative targets. */
	f = fopen(data, "w");
	if (!f) { perror("fopen data"); return 1; }
	fputs("L2S-REGRESSION-PAYLOAD", f);
	fclose(f);
	if (symlink(".l2s.base0001.0003", sentinel) != 0) { perror("symlink sentinel"); return 1; }
	if (symlink(".l2s.base0001", base) != 0) { perror("symlink base"); return 1; }
	if (symlink(".l2s.base0001", link1) != 0) { perror("symlink link1"); return 1; }
	if (symlink(".l2s.base0001", link2) != 0) { perror("symlink link2"); return 1; }

	/* 2. THE F-1 CHECKS — all EPERM before the fix: */
	errno = 0;
	if (lstat(base, &st) == 0) {
		check(S_ISREG(st.st_mode), "member lstat shows a regular file (hardlink emulation)");
		check(st.st_nlink == 3, "member st_nlink == 3 (family size)");
	} else {
		check(0, "lstat(member) — the dpkg 'unable to stat' gate");
	}

	errno = 0;
	check(lstat(sentinel, &st) == 0, "lstat(sentinel) — the ls -la / find / file path");

	errno = 0;
	check(stat(base, &st) == 0, "stat(member) follow still works (cat/execve path)");

	/* 3. rename() over a member — dpkg's replace dance. */
	snprintf(tmpname, sizeof tmpname, "%s/base.dpkg-new", dir);
	f = fopen(tmpname, "w");
	if (f) { fputs("REPLACED", f); fclose(f); }
	errno = 0;
	check(rename(tmpname, base) == 0, "rename over member (dpkg replace)");

	/* 4. link() onto the surviving family members — EPERM before the fix
	 *    (move_and_symlink_path's !first_link branch read the sentinel
	 *    against PRoot's cwd). */
	errno = 0;
	snprintf(newname, sizeof newname, "%s/hardlink3", dir);
	check(link(link1, newname) == 0, "link onto existing relative family");

	/* 5. unlink() down the family; the last unlink must clean up. */
	errno = 0;
	check(unlink(link2) == 0, "unlink member 2");
	errno = 0;
	check(unlink(newname) == 0, "unlink member 3");
	errno = 0;
	check(unlink(link1) == 0, "unlink member 1 (family now 0: cleanup)");
	errno = 0;
	check(lstat(sentinel, &st) == -1 && errno == ENOENT,
	      "sentinel removed after last unlink");

	if (failures == 0) {
		printf("L2S-RELATIVE-TARGETS OK\n");
		return 0;
	}
	printf("L2S-RELATIVE-TARGETS FAILED (%d)\n", failures);
	return 1;
}
