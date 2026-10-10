
#include <sys/prctl.h>
#include <unistd.h>
int main() {
    prctl(PR_SET_NAME, "supervisor");
    if (fork() == 0) prctl(PR_SET_NAME, "TEESimulator");
    for (;;) pause();
}
