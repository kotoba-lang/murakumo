/* C twin of kernel.kotoba.in: nearest-neighbour 2x upsample of a [C,H,W] f32
 * tensor to [C,2H,2W] (the UNet/VAE decoder's Upsample before its 3x3 conv).
 * Pure data movement, so the result is exact by construction; the gate guards
 * the indexing.
 *   cc -O2 -ffp-contract=off -I.. -DC=32 -DH=24 -DW=40 -DREPS=1 */
#include "../kern.h"
#ifndef C
#define C 32
#endif
#ifndef H
#define H 24
#endif
#ifndef W
#define W 40
#endif
#ifndef REPS
#define REPS 1
#endif
static float x_[C*H*W];
static int64_t fill(void){ return fill16(x_,C*H*W,12345,0x39000000u,0); }
static float out_at(int c,int y,int x){ return x_[(c*H+y/2)*W+x/2]; }
#ifndef NO_MAIN
int main(void){
  int64_t cs=fill()&MASK48;
  for(int rep=0;rep<REPS;rep++) for(int c=0;c<C;c++) for(int y=0;y<2*H;y++) for(int x=0;x<2*W;x++)
    cs=(cs*31+(int64_t)B(out_at(c,y,x)))&MASK48;
  printf("%lld\n",(long long)cs); return 0;
}
#endif
