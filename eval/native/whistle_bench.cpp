#include "cactus.h"
#include <chrono>
#include <cstdio>
#include <fstream>
#include <string>
#include <thread>
#include <vector>
using Clock = std::chrono::steady_clock;
double elapsed(Clock::time_point t) {
  return std::chrono::duration<double>(Clock::now() - t).count();
}
std::vector<char> read(const char *p) {
  std::ifstream f(p, std::ios::binary | std::ios::ate);
  if (!f)
    exit(2);
  size_t n = f.tellg();
  f.seekg(0);
  std::vector<char> a(n);
  f.read(a.data(), n);
  if (!f)
    exit(2);
  return a;
}
void check(int rc) {
  if (rc < 0) {
    fprintf(stderr, "cactus error: %s\n", cactus_last_error());
    exit(1);
  }
}
int main(int argc, char **argv) {
  if (argc != 4)
    return 2;
  auto weights = read(argv[1]);
  auto raw = read(argv[2]);
  if (raw.size() % 4)
    return 2;
  size_t n = raw.size() / 4;
  const float *a = (const float *)raw.data();
  double duration = n / 16000.0;
  cactus_set_threads(4);
  auto load = Clock::now();
  check(cactus_load((const unsigned char *)weights.data(), weights.size()));
  double load_s = elapsed(load);
  printf("{\"threads\":4,\"model_load_s\":%.9f,\"audio_duration_s\":%.9f,"
         "\"runs\":[",
         load_s, duration);
  fflush(stdout);
  std::vector<char> out(1 << 20);
  for (int run = 0; run < 2; ++run) {
    auto start = Clock::now();
    double compute_s = 0;
    if (std::string(argv[3]) == "batch") {
      check(cactus_transcribe(a, n, "en", nullptr, 1, out.data(), out.size()));
      compute_s = elapsed(start);
      printf("%s{\"elapsed_s\":%.9f,\"compute_s\":%.9f,\"rtf\":%.9f,\"result\":"
             "%s}",
             run ? "," : "", elapsed(start), compute_s, compute_s / duration,
             out.data());
    } else {
      printf("%s{\"events\":[", run ? "," : "");
      bool first = true;
      for (size_t i = 0; i < n; i += 16000) {
        std::this_thread::sleep_until(
            start + std::chrono::duration_cast<Clock::duration>(
                        std::chrono::duration<double>(
                            (i + std::min<size_t>(16000, n - i)) / 16000.0)));
        auto c = Clock::now();
        check(cactus_stream_transcribe_process(
            a + i, std::min<size_t>(16000, n - i), "en", nullptr, out.data(),
            out.size()));
        double cs = elapsed(c);
        compute_s += cs;
        printf("%s{\"t_recv\":%.9f,\"compute_s\":%.9f,\"result\":%s}",
               first ? "" : ",", elapsed(start), cs, out.data());
        first = false;
        fflush(stdout);
      }
      auto c = Clock::now();
      check(cactus_stream_transcribe_stop(out.data(), out.size()));
      compute_s += elapsed(c);
      printf(",{\"t_recv\":%.9f,\"result\":%s}],\"elapsed_s\":%.9f,\"compute_"
             "s\":%.9f,\"rtf\":%.9f}",
             elapsed(start), out.data(), elapsed(start), compute_s,
             compute_s / duration);
    }
    fflush(stdout);
  }
  puts("]}");
}
