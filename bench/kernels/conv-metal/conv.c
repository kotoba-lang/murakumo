/* C twin of conv_metal.kotoba.in: 3 x 3 convolution, stride 1, padding 1, NCHW, batch 1,
 * no bias, X and W small integers (the LCG draw & VMASK, minus VOFF: -64..63 by default), accumulated
 * exactly in int64. Every product is at most (VOFF)^2 and CIN*9*that < 2^24 (true for the defaults up to CIN = 455), so the GPU's
 * float32 sums are exact integers and independent of the matrix units' addition order.
 * The checksum folds the float32 bit pattern of each out[co][oy*W+ox] in row-major order.
 *   cc -O2 -DCIN=32 -DCOUT=64 -DH=32 -DW=32 conv.c -o conv */
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#ifndef VMASK
#define VMASK 127
#endif
#ifndef VOFF
#define VOFF 64
#endif
#ifndef CIN
#define CIN 32
#endif
#ifndef COUT
#define COUT 64
#endif
#ifndef H
#define H 32
#endif
#ifndef W
#define W 32
#endif
static int64_t lcg(int64_t s){return (s*1103515245LL+12345LL)&2147483647LL;}
static int16_t x_[CIN*H*W], w_[COUT*CIN*9];
int main(void){
  int64_t s=12345;
  for(int i=0;i<CIN*H*W;i++){s=lcg(s);x_[i]=(int16_t)(((s>>8)&VMASK)-VOFF);}
  for(int i=0;i<COUT*CIN*9;i++){s=lcg(s);w_[i]=(int16_t)(((s>>8)&VMASK)-VOFF);}
  int64_t c=s&281474976710655LL;
  for(int co=0;co<COUT;co++) for(int oy=0;oy<H;oy++) for(int ox=0;ox<W;ox++){
    int64_t acc=0;
    for(int ci=0;ci<CIN;ci++) for(int ky=0;ky<3;ky++) for(int kx=0;kx<3;kx++){
      int yy=oy+ky-1, xx=ox+kx-1; if(yy<0||yy>=H||xx<0||xx>=W) continue;
      acc+=(int64_t)w_[((co*CIN+ci)*3+ky)*3+kx]*x_[(ci*H+yy)*W+xx];
    }
    float f=(float)acc; uint32_t u; memcpy(&u,&f,4);
    c=(c*31+(int64_t)u)&281474976710655LL;
  }
  printf("%lld\n",(long long)c); return 0;
}
