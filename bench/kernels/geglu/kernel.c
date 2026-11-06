/* C twin of kernel.kotoba.in: GEGLU in f32. x is [R, 2N]; the first N columns
 * are the value a, the last N the gate g; out = a * gelu(g) with
 * gelu(g) = 0.5 * g * (1 + erf(g / sqrt(2))). The language has no erf, so erf is
 * Abramowitz-Stegun 7.1.26 (|error| <= 1.5e-7 in erf) built from the fixed
 * polynomial expf_k. Each step is one binary32 operation.
 *   cc -O2 -ffp-contract=off -I.. -DR=32 -DN=320 -DREPS=1 */
#include "../kern.h"
#ifndef R
#define R 32
#endif
#ifndef N
#define N 320
#endif
#ifndef REPS
#define REPS 1
#endif
static float x_[R*2*N];
static int64_t fill(void){ return fill16(x_,R*2*N,12345,0x39800000u,0); }
static float erf_k(float x){
  float ax=fabsf(x);
  float t=F(0x3F800000u)/(F(0x3F800000u)+F(0x3EA7BA05u)*ax);
  float p=F(0x3F87DC22u);
  p=p*t; p=p+F(0xBFBA00E3u);
  p=p*t; p=p+F(0x3FB5F0E3u);
  p=p*t; p=p+F(0xBE91A98Eu);
  p=p*t; p=p+F(0x3E827906u);
  p=p*t;
  float e=expf_k(-(ax*ax));
  float r=F(0x3F800000u)-p*e;
  return x<0 ? -r : r;
}
static float out_at(int r,int i){
  float a=x_[r*2*N+i], g=x_[r*2*N+N+i];
  float e=erf_k(g*F(0x3F3504F3u));
  float gelu=(F(0x3F000000u)*g)*(F(0x3F800000u)+e);
  return a*gelu;
}
#ifndef NO_MAIN
int main(void){
  int64_t c=fill()&MASK48;
  for(int rep=0;rep<REPS;rep++) for(int r=0;r<R;r++) for(int i=0;i<N;i++)
    c=(c*31+(int64_t)B(out_at(r,i)))&MASK48;
  printf("%lld\n",(long long)c); return 0;
}
#endif
