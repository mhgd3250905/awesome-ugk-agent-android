#include <stdio.h>
#include <unistd.h>

int main(int argc, char *argv[]) {
    printf("ugk_runtime_probe=1\n");
    printf("pid=%ld\n", (long) getpid());
    printf("argc=%d\n", argc);

    for (int index = 0; index < argc; index++) {
        printf("argv[%d]=%s\n", index, argv[index]);
    }

    fflush(stdout);
    return 0;
}
