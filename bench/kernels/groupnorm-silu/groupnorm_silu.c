/* C twin of groupnorm_silu.kotoba.in: GroupNorm (affine) + SiLU on one
 * [C, HW] f32 tensor, the op that opens every SDXL UNet/VAE ResBlock.
 * Every operation is one binary32 step, in the same order as the Kotoba
 * kernel; build with -ffp-contract=off (no fused multiply-add) and without
 * -ffast-math, or the checksum will not match.
 *   cc -O2 -ffp-contract=off -DC=320 -DHW=1024 -DG=32 -DREPS=1 */
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <math.h>
#ifndef C
#define C 320
#endif
#ifndef HW
#define HW 1024
#endif
#ifndef G
#define G 32
#endif
#ifndef REPS
#define REPS 1
#endif
static float F(uint32_t u){float f;memcpy(&f,&u,4);return f;}
static uint32_t B(float f){uint32_t u;memcpy(&u,&f,4);return u;}
static int64_t lcg(int64_t s){return (s*1103515245LL+12345LL)&2147483647LL;}
static float expf_k(float x){
  float lim=F(0x42AE0000u);
  x=fminf(x,lim); x=fmaxf(x,-lim);
  float fx=x*F(0x3FB8AA3Bu); fx=fx+F(0x4B400000u);
  int32_t k=(int32_t)(B(fx)&0x7FFFFFu)-0x400000;
  float kf=fx-F(0x4B400000u);
  float r=x-kf*F(0x3F318000u); r=r-kf*F(0xB95E8083u);
  float y=F(0x39506967u);
  y=y*r; y=y+F(0x3AB743CEu);
  y=y*r; y=y+F(0x3C088908u);
  y=y*r; y=y+F(0x3D2AA9C1u);
  y=y*r; y=y+F(0x3E2AAAAAu);
  y=y*r; y=y+F(0x3F000000u);
  float p=y*r; p=p*r; p=p+r; p=p+F(0x3F800000u);
  return p*F((uint32_t)(k+127)<<23);
}
static float x_[C*HW], g_[C], b_[C];
static int64_t fill(void){
  int64_t s=12345;
  for(int i=0;i<C*HW;i++){s=lcg(s);int64_t v=((s>>8)&65535)-32768;x_[i]=(float)v*F(0x39000000u);}
  for(int i=0;i<C;i++){s=lcg(s);int64_t v=(s>>8)&1023;g_[i]=(float)v*F(0x3A800000u)+F(0x3F000000u);}
  for(int i=0;i<C;i++){s=lcg(s);int64_t v=((s>>8)&65535)-32768;b_[i]=(float)v*F(0x38800000u);}
  return s;
}
int main(void){
  int64_t c=fill()&281474976710655LL;
  float eps=(float)1e-5, inv_n=F(0x3F800000u)/(float)((C/G)*HW);
  for(int rep=0;rep<REPS;rep++){
    for(int g=0;g<G;g++){
      int base=g*(C/G)*HW, n=(C/G)*HW;
      float sum=0; for(int i=0;i<n;i++) sum=sum+x_[base+i];
      float mean=sum*inv_n;
      float sq=0; for(int i=0;i<n;i++){float d=x_[base+i]-mean; sq=sq+d*d;}
      float var=sq*inv_n;
      float inv=F(0x3F800000u)/sqrtf(var+eps);
      for(int i=0;i<n;i++){
        int ch=g*(C/G)+i/HW;
        float y=(x_[base+i]-mean)*inv; y=y*g_[ch]; y=y+b_[ch];
        float e=expf_k(-y); e=e+F(0x3F800000u); float o=y/e;
        c=(c*31+(int64_t)B(o))&281474976710655LL;
      }
    }
  }
  printf("%lld\n",(long long)c);
  return 0;
}
