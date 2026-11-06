/* Shared by the kernels' C twins: bit casts, the 31-bit LCG every kernel fills
 * its tensors from, and the fixed-polynomial expf the Kotoba kernels also use
 * (the language has no exp). Compile with -ffp-contract=off, no -ffast-math. */
#ifndef KERN_H
#define KERN_H
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <math.h>
#define MASK48 281474976710655LL
static float F(uint32_t u){float f;memcpy(&f,&u,4);return f;}
static uint32_t B(float f){uint32_t u;memcpy(&u,&f,4);return u;}
static int64_t lcg(int64_t s){return (s*1103515245LL+12345LL)&2147483647LL;}
/* fill n floats with (signed 16-bit LCG draw) * scale + add; returns the next seed.
 * 2^-12 = 0x39800000, 2^-13 = 0x39000000, 2^-14 = 0x38800000, 2^-16 = 0x37800000. */
static int64_t fill16(float* a,int n,int64_t s,uint32_t scalebits,uint32_t addbits){
  for(int i=0;i<n;i++){s=lcg(s);int64_t v=((s>>8)&65535)-32768;float t=(float)v*F(scalebits);a[i]=t+F(addbits);}
  return s;
}
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
#endif
