// fnv <file> < offset:size lines  → prints fnv1a64 hex per line
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
int main(int argc, char **argv) {
    int fd = open(argv[1], O_RDONLY);
    struct stat st; fstat(fd, &st);
    uint8_t *m = mmap(NULL, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    if (m == MAP_FAILED) { perror("mmap"); return 1; }
    unsigned long long off, sz;
    while (scanf("%llu:%llu", &off, &sz) == 2) {
        uint64_t h = 0xcbf29ce484222325ULL;
        const uint8_t *p = m + off, *e = p + sz;
        for (; p < e; p++) { h ^= *p; h *= 0x100000001b3ULL; }
        printf("%016llx\n", (unsigned long long)h);
        fflush(stdout);
    }
    return 0;
}
