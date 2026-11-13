/*
 * Regression test for proot-me/proot#182 (readlinkat empty pathname).
 * Adapted from upstream PR #415 test/test-readlinkat-empty-pathname.c.
 *
 * readlinkat(2) with an empty pathname (valid since Linux 2.6.39 when
 * dirfd was opened with O_PATH|O_NOFOLLOW) used to make proot's
 * readlinkat exit handler pass an empty referrer to detranslate_path(),
 * triggering assert(length2 > 0) in compare_paths2 (path.c:547) and
 * aborting the tracer.
 *
 * Build: gcc -o test-readlinkat-empty-pathname test-readlinkat-empty-pathname.c
 * Run inside a proot session:  proot ./test-readlinkat-empty-pathname
 * Expected: exit 0. Before the fix: proot aborts with
 * "Assertion failed: length2 > 0 (./path/path.c: compare_paths2: 547)".
 */
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(void)
{
	char link_path[PATH_MAX];
	char target[PATH_MAX];
	ssize_t len;
	int fd;

	snprintf(link_path, sizeof(link_path), "/tmp/test-bug-182-%d",
		 (int) getpid());

	if (symlink("/tmp", link_path) < 0) {
		perror("symlink");
		exit(EXIT_FAILURE);
	}

	/* Open the symlink itself (not its target) — Linux >= 2.6.39. */
	fd = open(link_path, O_PATH | O_NOFOLLOW);
	if (fd < 0) {
		unlink(link_path);
		if (errno == EINVAL)
			exit(125);	/* O_PATH unsupported, skip */
		perror("open");
		exit(EXIT_FAILURE);
	}

	/* readlinkat with empty pathname operates on the symlink fd itself.
	 * Before the fix this crashed proot via assert(length2 > 0) in
	 * compare_paths2 because detranslate_path received an empty referrer. */
	len = readlinkat(fd, "", target, sizeof(target) - 1);
	if (len < 0) {
		close(fd);
		unlink(link_path);
		if (errno == ENOENT || errno == EINVAL)
			exit(125);	/* kernel doesn't support this, skip */
		perror("readlinkat");
		exit(EXIT_FAILURE);
	}
	target[len] = '\0';

	close(fd);
	unlink(link_path);

	if (strcmp(target, "/tmp") != 0) {
		fprintf(stderr, "readlinkat: unexpected target '%s'\n",
			target);
		exit(EXIT_FAILURE);
	}

	exit(EXIT_SUCCESS);
}
