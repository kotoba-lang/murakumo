/* C twin of kernel.kotoba.in: an SDXL-style ResBlock in f32, composed from the
 * single-kernel slices (GroupNorm+SiLU, 3x3 conv, linear on silu(temb), 1x1 skip
 * conv, residual add):
 *   a  = silu(groupnorm(x));        h1 = conv3x3(a) + linear(silu(temb))[c]
 *   b  = silu(groupnorm(h1));       h2 = conv3x3(b)
 *   out = conv1x1(x) + h2
 * x is [CI,H,W], out is [CO,H,W], temb is [TE]. The pipeline lives in impl.h
 * (shared with the f64 reference). Build with -ffp-contract=off.
 *   cc -O2 -ffp-contract=off -I.. -DCI=16 -DCO=32 -DH=16 -DW=16 -DG=8 -DTE=64 -DREPS=1 */
#include "../kern.h"
#ifndef CI
#define CI 16
#endif
#ifndef CO
#define CO 32
#endif
#ifndef H
#define H 16
#endif
#ifndef W
#define W 16
#endif
#ifndef G
#define G 8
#endif
#ifndef TE
#define TE 64
#endif
#ifndef REPS
#define REPS 1
#endif
#define REAL float
#define SUF _f
#define IS_DOUBLE 0
#include "impl.h"
#undef REAL
#undef SUF
#undef IS_DOUBLE
/* parameters, drawn from one LCG stream in this order */
static float x_[CI*H*W], temb_[TE], g1_[CI], b1_[CI], w1_[CO*CI*9], c1_[CO], wt_[TE*CO], bt_[CO],
             g2_[CO], b2_[CO], w2_[CO*CO*9], c2_[CO], ws_[CO*CI], bs_[CO];
static int64_t fill(void){
  int64_t s=12345;
  s=fill16(x_,CI*H*W,s,0x39000000u,0);   s=fill16(temb_,TE,s,0x39800000u,0);
  s=fill16(g1_,CI,s,0x37800000u,0x3F800000u); s=fill16(b1_,CI,s,0x38800000u,0);
  s=fill16(w1_,CO*CI*9,s,0x37000000u,0); s=fill16(c1_,CO,s,0x38800000u,0);
  s=fill16(wt_,TE*CO,s,0x37000000u,0);   s=fill16(bt_,CO,s,0x38800000u,0);
  s=fill16(g2_,CO,s,0x37800000u,0x3F800000u); s=fill16(b2_,CO,s,0x38800000u,0);
  s=fill16(w2_,CO*CO*9,s,0x37000000u,0); s=fill16(c2_,CO,s,0x38800000u,0);
  s=fill16(ws_,CO*CI,s,0x37000000u,0);   s=fill16(bs_,CO,s,0x38800000u,0);
  return s;
}
static float a_[CI*H*W], h1_[CO*H*W], tp_[CO], bb_[CO*H*W], h2_[CO*H*W], sk_[CO*H*W], out_[CO*H*W];
static void run_f(void){
  shape_f s={CI,CO,H,W,G,TE};
  resblock_f(s,x_,temb_,g1_,b1_,w1_,c1_,wt_,bt_,g2_,b2_,w2_,c2_,ws_,bs_,a_,h1_,tp_,bb_,h2_,sk_,out_);
}
#ifndef NO_MAIN
int main(void){
  int64_t c=fill()&MASK48;
  for(int rep=0;rep<REPS;rep++){
    run_f();
    for(int i=0;i<CO*H*W;i++) c=(c*31+(int64_t)B(out_[i]))&MASK48;
  }
  printf("%lld\n",(long long)c); return 0;
}
#endif
