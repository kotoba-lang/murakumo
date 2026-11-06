/* f64 reference for the SpatialTransformer block: the same pipeline (impl.h) with
 * REAL = double, libm exp/sqrt/erf (so the f32 twin's polynomial erf and exp show
 * up in the error), fed the same float inputs. */
#define NO_MAIN
#include "kernel.c"
#define REAL double
#define SUF _d
#define IS_DOUBLE 1
#include "impl.h"
#define DD(name,n) static double name[n]
DD(xd,SC*NPOS); DD(cxd,SLC*SCC); DD(gngd,SC); DD(gnbd,SC); DD(wid,SC*SC); DD(bid,SC);
DD(l1gd,SC); DD(l1bd,SC); DD(wq1d,SC*SC); DD(wk1d,SC*SC); DD(wv1d,SC*SC); DD(wo1d,SC*SC); DD(bo1d,SC);
DD(l2gd,SC); DD(l2bd,SC); DD(wq2d,SC*SC); DD(wk2d,SCC*SC); DD(wv2d,SCC*SC); DD(wo2d,SC*SC); DD(bo2d,SC);
DD(l3gd,SC); DD(l3bd,SC); DD(wf1d,SC*2*SFI); DD(bf1d,2*SFI); DD(wf2d,SFI*SC); DD(bf2d,SC); DD(woutd,SC*SC); DD(boutd,SC); DD(zerod,SC);
DD(gnd,SC*NPOS); DD(tokd,NPOS*SC); DD(t0d,NPOS*SC); DD(lnd,NPOS*SC); DD(qd,NPOS*SC); DD(kd,MAXN*SC); DD(vd,MAXN*SC); DD(atd,NPOS*SC);
DD(od,NPOS*SC); DD(t1d,NPOS*SC); DD(t2d,NPOS*SC); DD(ffd,NPOS*2*SFI); DD(ggd,NPOS*SFI); DD(f2d,NPOS*SC); DD(t3d,NPOS*SC); DD(pd,NPOS*SC);
DD(scd,MAXN); DD(outd,SC*NPOS);
#define CP(dst,src,n) for(int i_=0;i_<(n);i_++) dst[i_]=(double)src[i_]
int main(void){
  fill(); run_f();
  CP(xd,x_,SC*NPOS); CP(cxd,ctx_,SLC*SCC); CP(gngd,gng_,SC); CP(gnbd,gnb_,SC); CP(wid,wi_,SC*SC); CP(bid,bi_,SC);
  CP(l1gd,l1g_,SC); CP(l1bd,l1b_,SC); CP(wq1d,wq1_,SC*SC); CP(wk1d,wk1_,SC*SC); CP(wv1d,wv1_,SC*SC); CP(wo1d,wo1_,SC*SC); CP(bo1d,bo1_,SC);
  CP(l2gd,l2g_,SC); CP(l2bd,l2b_,SC); CP(wq2d,wq2_,SC*SC); CP(wk2d,wk2_,SCC*SC); CP(wv2d,wv2_,SCC*SC); CP(wo2d,wo2_,SC*SC); CP(bo2d,bo2_,SC);
  CP(l3gd,l3g_,SC); CP(l3bd,l3b_,SC); CP(wf1d,wf1_,SC*2*SFI); CP(bf1d,bf1_,2*SFI); CP(wf2d,wf2_,SFI*SC); CP(bf2d,bf2_,SC);
  CP(woutd,wout_,SC*SC); CP(boutd,bout_,SC);
  shape_d s={SC,SH,SW,SG,SNH,SLC,SCC,SFI};
  params_d P={gngd,gnbd,wid,bid,l1gd,l1bd,wq1d,wk1d,wv1d,wo1d,bo1d,l2gd,l2bd,wq2d,wk2d,wv2d,wo2d,bo2d,
              l3gd,l3bd,wf1d,bf1d,wf2d,bf2d,woutd,boutd,zerod};
  scratch_d Wk={gnd,tokd,t0d,lnd,qd,kd,vd,atd,od,t1d,t2d,ffd,ggd,f2d,t3d,pd,scd};
  spatial_d(s,P,xd,cxd,Wk,outd);
  double maxabs=0,maxref=0; for(int i=0;i<SC*NPOS;i++){ double e=fabs((double)out_[i]-outd[i]); if(e>maxabs)maxabs=e; if(fabs(outd[i])>maxref)maxref=fabs(outd[i]); }
  printf("elements %d max_abs_err %.3e max_abs_ref %.3e\n",SC*NPOS,maxabs,maxref); return 0;}
