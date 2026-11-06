/* C twin of kernel.kotoba.in: sinusoidal timestep embedding in f32, the first
 * stage of an SDXL UNet's time conditioning. For t = TS*i (i < NT) and half =
 * DIM/2: freq_k = exp(-ln(10000) * k / half), arg = t * freq_k, and the
 * embedding is [cos(arg_0..), sin(arg_0..)]. The language has no sin or cos, so
 * both come from a quadrant reduction by pi/2 in three exactly-representable
 * parts and the cephes degree-5/6 polynomials; exp is the fixed expf_k.
 * Each step is one binary32 operation.
 *   cc -O2 -ffp-contract=off -I.. -DDIM=320 -DNT=64 -DTS=16 -DREPS=1 */
#include "../kern.h"
#ifndef DIM
#define DIM 320
#endif
#ifndef NT
#define NT 64
#endif
#ifndef TS
#define TS 16
#endif
#ifndef REPS
#define REPS 1
#endif
#define HALF (DIM/2)
/* returns the reduced argument; *q is the quadrant (0..3). pi/2 = c1 + c2 + c3
 * with c1 = 1.570556640625 and c2 = 2.396106719970703e-4 short enough that
 * kf*c1 and kf*c2 are exact for kf < 2^10. */
static float reduce(float x,int* q){
  float fx=x*F(0x3F22F983u); fx=fx+F(0x4B400000u);
  *q=((int32_t)(B(fx)&0x7FFFFFu)-0x400000)&3;
  float kf=fx-F(0x4B400000u);
  float r=x-kf*F(0x3FC90800u); r=r-kf*F(0x397B4000u); r=r-kf*F(0x33A22169u);
  return r;
}
static float sinp(float r){
  float z=r*r;
  float p=F(0xB94CA1F9u)*z; p=p+F(0x3C08839Eu); p=p*z; p=p+F(0xBE2AAAA3u); p=p*z; p=p*r;
  return p+r;
}
static float cosp(float r){
  float z=r*r;
  float q=F(0x37CCF5CEu)*z; q=q+F(0xBAB6061Au); q=q*z; q=q+F(0x3D2AAAA5u);
  float zz=z*z; q=zz*q; float hz=F(0x3F000000u)*z;
  return (F(0x3F800000u)-hz)+q;
}
static float cos_k(float x){ int q; float r=reduce(x,&q); float s=sinp(r), c=cosp(r);
  return q==0?c:q==1?-s:q==2?-c:s; }
static float sin_k(float x){ int q; float r=reduce(x,&q); float s=sinp(r), c=cosp(r);
  return q==0?s:q==1?c:q==2?-s:-c; }
static float arg_at(int ti,int k){
  float freq=expf_k(F(0xC1135D8Eu)*((float)k/(float)HALF));
  return (float)(TS*ti)*freq;
}
#ifndef NO_MAIN
int main(void){
  int64_t c=12345&MASK48;
  for(int rep=0;rep<REPS;rep++) for(int ti=0;ti<NT;ti++){
    for(int k=0;k<HALF;k++) c=(c*31+(int64_t)B(cos_k(arg_at(ti,k))))&MASK48;
    for(int k=0;k<HALF;k++) c=(c*31+(int64_t)B(sin_k(arg_at(ti,k))))&MASK48;
  }
  printf("%lld\n",(long long)c); return 0;
}
#endif
