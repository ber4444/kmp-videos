#include "moonshine-c-api.h"
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
std::string escape(const char *p) {
  std::string s;
  for (; *p; ++p) {
    if (*p == '"' || *p == '\\')
      s += '\\';
    if (*p == '\n')
      s += "\\n";
    else if (*p == '\r')
      s += "\\r";
    else if (*p == '\t')
      s += "\\t";
    else
      s += *p;
  }
  return s;
}
void check(int rc) {
  if (rc < 0) {
    fprintf(stderr, "moonshine error %d: %s\n", rc,
            moonshine_error_to_string(rc));
    exit(1);
  }
}
int main(int argc, char **argv) {
  if (argc != 4) {
    fprintf(stderr, "usage: bench modeldir audio.f32 batch|stream\n");
    return 2;
  }
  std::ifstream f(argv[2], std::ios::binary | std::ios::ate);
  if (!f) {
    return 2;
  }
  size_t n = f.tellg();
  f.seekg(0);
  if (n % 4) {
    return 2;
  }
  std::vector<float> a(n / 4);
  f.read((char *)a.data(), n);
  if (!f) {
    return 2;
  }
  const double duration = a.size() / 16000.0;
  auto load = Clock::now();
  auto h = moonshine_load_transcriber_from_files(
      argv[1], MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING, nullptr, 0,
      MOONSHINE_HEADER_VERSION);
  check(h);
  const double load_s = elapsed(load);
  printf("{\"runtime_version\":%d,\"header_version\":%d,\"model_load_s\":%.9f,"
         "\"audio_duration_s\":%.9f,\"runs\":[",
         moonshine_get_version(), MOONSHINE_HEADER_VERSION, load_s, duration);
  fflush(stdout);
  for (int run = 0; run < 2; ++run) {
    transcript_t *t = nullptr;
    auto start = Clock::now();
    double compute_s = 0, first_text_s = -1, first_complete_s = -1,
           max_update_s = 0;
    std::string text;
    if (std::string(argv[3]) == "batch") {
      auto c = Clock::now();
      check(moonshine_transcribe_without_streaming(h, a.data(), a.size(), 16000,
                                                   0, &t));
      compute_s = elapsed(c);
    } else {
      auto stream = moonshine_create_stream(h, 0);
      check(stream);
      check(moonshine_start_stream(h, stream));
      for (size_t i = 0; i < a.size(); i += 3200) {
        std::this_thread::sleep_until(
            start +
            std::chrono::duration_cast<Clock::duration>(
                std::chrono::duration<double>(
                    (i + std::min<size_t>(3200, a.size() - i)) / 16000.0)));
        auto c = Clock::now();
        check(moonshine_transcribe_add_audio_to_stream(
            h, stream, a.data() + i, std::min<size_t>(3200, a.size() - i),
            16000, 0));
        // Update every 600 ms, with 200 ms input chunks.
        if ((i / 3200) % 3 == 2) {
          check(moonshine_transcribe_stream(h, stream, 0, &t));
          if (t)
            for (size_t j = 0; j < t->line_count; ++j) {
              if (t->lines[j].text && *t->lines[j].text && first_text_s < 0)
                first_text_s = elapsed(start);
              if (t->lines[j].is_complete && first_complete_s < 0)
                first_complete_s = elapsed(start);
            }
        }
        double step_s = elapsed(c);
        compute_s += step_s;
        max_update_s = std::max(max_update_s, step_s);
      }
      auto c = Clock::now();
      check(moonshine_stop_stream(h, stream));
      check(moonshine_transcribe_stream(h, stream, 0, &t));
      compute_s += elapsed(c);
      // Copy the transcript before freeing its owning stream.
      if (t)
        for (size_t i = 0; i < t->line_count; ++i) {
          if (i)
            text += ' ';
          text += t->lines[i].text;
        }
      check(moonshine_free_stream(h, stream));
    }
    const double wall = elapsed(start);
    if (std::string(argv[3]) == "batch" && t)
      for (size_t i = 0; i < t->line_count; ++i) {
        if (i)
          text += ' ';
        text += t->lines[i].text;
      }
    printf("%s{\"elapsed_s\":%.9f,\"compute_s\":%.9f,\"rtf\":%.9f,\"first_text_"
           "s\":%.9f,\"first_complete_s\":%.9f,\"max_update_s\":%.9f,\"text\":"
           "\"%s\"}",
           run ? "," : "", wall, compute_s, compute_s / duration, first_text_s,
           first_complete_s, max_update_s, escape(text.c_str()).c_str());
    fflush(stdout);
  }
  puts("]}");
  moonshine_free_transcriber(h);
}
