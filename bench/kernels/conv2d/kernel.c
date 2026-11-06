/* C twin of kernel.kotoba.in: 2D convolution, NCHW, batch 1, bias, f32, with a
 * square window KS (1 or 3), stride STRIDE (1 or 2) and padding KS/2. It covers
 * the 1x1 projections, the 3x3 stride-1 convolutions (as conv2d-3x3 does) and the
 * 3x3 stride-2 downsample. The accumulator starts at the bias and adds one
 * product per in-bounds tap in (ci, ky, kx) order, each rounded alone.
 *   cc -O2 -ffp-contract=off -I.. -DCI=32 -DCO=32 -DH=32 -DW=32 -DKS=3 -DSTRIDE=2 -DREPS=1 */
#include "../kern.h"
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
#ifndef KS
#define KS 3
#endif
#ifndef STRIDE
#define STRIDE 2
#endif
#ifndef REPS
#define REPS 1
#endif
#define PAD (KS/2)
#define OH ((H+2*PAD-KS)/STRIDE+1)
#define OW ((W+2*PAD-KS)/STRIDE+1)
static float x_[CI*H*W], w_[CO*CI*KS*KS], b_[CO];
static int64_t fill(void){
  int64_t s=12345;
  s=fill16(x_,CI*H*W,s,0x38800000u,0); s=fill16(w_,CO*CI*KS*KS,s,0x37800000u,0); s=fill16(b_,CO,s,0x38800000u,0);
  return s;
}
static float conv_at(int co,int oy,int ox){
  float acc=b_[co];
  for(int ci=0;ci<CI;ci++) for(int ky=0;ky<KS;ky++) for(int kx=0;kx<KS;kx++){
    int yy=oy*STRIDE+ky-PAD, xx=ox*STRIDE+kx-PAD;
    if(yy<0||yy>=H||xx<0||xx>=W) continue;
    float p=w_[((co*CI+ci)*KS+ky)*KS+kx]*x_[(ci*H+yy)*W+xx];
    acc=acc+p;
  }
  return acc;
}
#ifndef NO_MAIN
int main(void){
  int64_t c=fill()&MASK48;
  for(int rep=0;rep<REPS;rep++) for(int co=0;co<CO;co++) for(int y=0;y<OH;y++) for(int x=0;x<OW;x++)
    c=(c*31+(int64_t)B(conv_at(co,y,x)))&MASK48;
  printf("%lld\n",(long long)c); return 0;
}
#endif
