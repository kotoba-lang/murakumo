/* C twin of conv2d_3x3.kotoba.in: 3x3 convolution, stride 1, zero padding 1,
 * NCHW, batch 1, with bias, f32. The accumulator starts at the bias and adds
 * one product per in-bounds tap in (ci, ky, kx) order; each multiply and add
 * rounds on its own. Build with -ffp-contract=off and without -ffast-math.
 *   cc -O2 -ffp-contract=off -DCI=32 -DCO=32 -DH=32 -DW=32 -DREPS=1 */
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#ifndef CI
#define CI 32
#endif
#ifndef CO
#define CO 32
#endif
#ifndef H
#define H 32
#endif
#ifndef W
#define W 32
#endif
#ifndef REPS
#define REPS 1
#endif
static float F(uint32_t u){float f;memcpy(&f,&u,4);return f;}
static uint32_t B(float f){uint32_t u;memcpy(&u,&f,4);return u;}
static int64_t lcg(int64_t s){return (s*1103515245LL+12345LL)&2147483647LL;}
static float x_[CI*H*W], w_[CO*CI*9], b_[CO];
static int64_t fill(void){
  int64_t s=12345;
  for(int i=0;i<CI*H*W;i++){s=lcg(s);int64_t v=((s>>8)&65535)-32768;x_[i]=(float)v*F(0x38800000u);}   /* 2^-14 */
  for(int i=0;i<CO*CI*9;i++){s=lcg(s);int64_t v=((s>>8)&65535)-32768;w_[i]=(float)v*F(0x37800000u);} /* 2^-16 */
  for(int i=0;i<CO;i++){s=lcg(s);int64_t v=((s>>8)&65535)-32768;b_[i]=(float)v*F(0x38800000u);}
  return s;
}
static float conv_at(int co,int y,int x){
  float acc=b_[co];
  for(int ci=0;ci<CI;ci++) for(int ky=0;ky<3;ky++) for(int kx=0;kx<3;kx++){
    int yy=y+ky-1, xx=x+kx-1;
    if(yy<0||yy>=H||xx<0||xx>=W) continue;
    float p=w_[(co*CI+ci)*9+ky*3+kx]*x_[(ci*H+yy)*W+xx];
    acc=acc+p;
  }
  return acc;
}
#ifndef NO_MAIN
int main(void){
  int64_t c=fill()&281474976710655LL;
  for(int rep=0;rep<REPS;rep++)
    for(int co=0;co<CO;co++) for(int y=0;y<H;y++) for(int x=0;x<W;x++)
      c=(c*31+(int64_t)B(conv_at(co,y,x)))&281474976710655LL;
  printf("%lld\n",(long long)c);
  return 0;
}
#endif
