/* C twin of kernel.kotoba.in: one SDXL-style SpatialTransformer block in f32
 * (groupnorm, proj_in, self-attention, cross-attention over a context, GEGLU
 * feed-forward, proj_out, residual), composed from the single-kernel slices. The
 * pipeline is impl.h, shared with the f64 reference. Build with -ffp-contract=off.
 *   cc -O2 -ffp-contract=off -I.. -DSC=32 -DSH=8 -DSW=8 -DSG=8 -DSNH=2 -DSLC=16 -DSCC=24 -DSFI=64 -DREPS=1 */
#include "../kern.h"
#ifndef SC
#define SC 32
#endif
#ifndef SH
#define SH 8
#endif
#ifndef SW
#define SW 8
#endif
#ifndef SG
#define SG 8
#endif
#ifndef SNH
#define SNH 2
#endif
#ifndef SLC
#define SLC 16
#endif
#ifndef SCC
#define SCC 24
#endif
#ifndef SFI
#define SFI 64
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
#define NPOS (SH*SW)
#define MAXN (NPOS>SLC?NPOS:SLC)
#define ARR(name,n) static float name[n]
ARR(x_,SC*NPOS); ARR(ctx_,SLC*SCC);
ARR(gng_,SC); ARR(gnb_,SC); ARR(wi_,SC*SC); ARR(bi_,SC);
ARR(l1g_,SC); ARR(l1b_,SC); ARR(wq1_,SC*SC); ARR(wk1_,SC*SC); ARR(wv1_,SC*SC); ARR(wo1_,SC*SC); ARR(bo1_,SC);
ARR(l2g_,SC); ARR(l2b_,SC); ARR(wq2_,SC*SC); ARR(wk2_,SCC*SC); ARR(wv2_,SCC*SC); ARR(wo2_,SC*SC); ARR(bo2_,SC);
ARR(l3g_,SC); ARR(l3b_,SC); ARR(wf1_,SC*2*SFI); ARR(bf1_,2*SFI); ARR(wf2_,SFI*SC); ARR(bf2_,SC);
ARR(wout_,SC*SC); ARR(bout_,SC); ARR(zero_,SC);
static int64_t fill(void){
  int64_t s=12345; const uint32_t W13=0x39000000u,W12=0x39800000u,W14=0x38800000u,W16=0x37800000u,W17=0x37000000u,ONE=0x3F800000u;
  s=fill16(x_,SC*NPOS,s,W13,0);        s=fill16(ctx_,SLC*SCC,s,W12,0);
  s=fill16(gng_,SC,s,W16,ONE);       s=fill16(gnb_,SC,s,W14,0);
  s=fill16(wi_,SC*SC,s,W17,0);       s=fill16(bi_,SC,s,W14,0);
  s=fill16(l1g_,SC,s,W16,ONE);       s=fill16(l1b_,SC,s,W14,0);
  s=fill16(wq1_,SC*SC,s,W17,0);      s=fill16(wk1_,SC*SC,s,W17,0); s=fill16(wv1_,SC*SC,s,W17,0); s=fill16(wo1_,SC*SC,s,W17,0); s=fill16(bo1_,SC,s,W14,0);
  s=fill16(l2g_,SC,s,W16,ONE);       s=fill16(l2b_,SC,s,W14,0);
  s=fill16(wq2_,SC*SC,s,W17,0);      s=fill16(wk2_,SCC*SC,s,W17,0); s=fill16(wv2_,SCC*SC,s,W17,0); s=fill16(wo2_,SC*SC,s,W17,0); s=fill16(bo2_,SC,s,W14,0);
  s=fill16(l3g_,SC,s,W16,ONE);       s=fill16(l3b_,SC,s,W14,0);
  s=fill16(wf1_,SC*2*SFI,s,W17,0);   s=fill16(bf1_,2*SFI,s,W14,0); s=fill16(wf2_,SFI*SC,s,W17,0); s=fill16(bf2_,SC,s,W14,0);
  s=fill16(wout_,SC*SC,s,W17,0);     s=fill16(bout_,SC,s,W14,0);
  return s;
}
ARR(gn_,SC*NPOS); ARR(tok_,NPOS*SC); ARR(t0_,NPOS*SC); ARR(ln_,NPOS*SC); ARR(q_,NPOS*SC); ARR(k_,MAXN*SC); ARR(v_,MAXN*SC); ARR(at_,NPOS*SC);
ARR(o_,NPOS*SC); ARR(t1_,NPOS*SC); ARR(t2_,NPOS*SC); ARR(ff_,NPOS*2*SFI); ARR(gg_,NPOS*SFI); ARR(f2_,NPOS*SC); ARR(t3_,NPOS*SC); ARR(p_,NPOS*SC);
ARR(sc_,MAXN); ARR(out_,SC*NPOS);
static void run_f(void){
  shape_f s={SC,SH,SW,SG,SNH,SLC,SCC,SFI};
  params_f P={gng_,gnb_,wi_,bi_,l1g_,l1b_,wq1_,wk1_,wv1_,wo1_,bo1_,l2g_,l2b_,wq2_,wk2_,wv2_,wo2_,bo2_,
              l3g_,l3b_,wf1_,bf1_,wf2_,bf2_,wout_,bout_,zero_};
  scratch_f Wk={gn_,tok_,t0_,ln_,q_,k_,v_,at_,o_,t1_,t2_,ff_,gg_,f2_,t3_,p_,sc_};
  spatial_f(s,P,x_,ctx_,Wk,out_);
}
#ifndef NO_MAIN
int main(void){
  int64_t c=fill()&MASK48;
  for(int rep=0;rep<REPS;rep++){
    run_f();
    for(int i=0;i<SC*NPOS;i++) c=(c*31+(int64_t)B(out_[i]))&MASK48;
  }
  printf("%lld\n",(long long)c); return 0;
}
#endif
