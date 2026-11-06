/* Reference for resblock_metal.kotoba.in: the same ResBlock (bench/kernels/resblock/impl.h) in double
 * precision on the parameters the f32 twin draws (kernel.c's fill(): same LCG stream, same order, values
 * exact in float), compared with the GPU's raw float32 output on stdin. Prints the largest normalised error
 * |gpu - ref| / max(1, |ref|) and, for scale, the f32 CPU twin's error against the same reference.
 *   cc -O2 -ffp-contract=off -I.. -DCI=32 -DCO=64 -DH=16 -DW=16 -DG=32 -DTE=64 resblock_gpu_ref.c -o ref -lm */
#define NO_MAIN
#include "../resblock/kernel.c"
#define REAL double
#define SUF _d
#define IS_DOUBLE 1
#include "../resblock/impl.h"
#define D(name,n) static double name[n]
D(xd,CI*H*W); D(td,TE); D(g1d,CI); D(b1d,CI); D(w1d,CO*CI*9); D(c1d,CO); D(wtd,TE*CO); D(btd,CO);
D(g2d,CO); D(b2d,CO); D(w2d,CO*CO*9); D(c2d,CO); D(wsd,CO*CI); D(bsd,CO);
D(ad,CI*H*W); D(h1d,CO*H*W); D(tpd,CO); D(bbd,CO*H*W); D(h2d,CO*H*W); D(skd,CO*H*W); D(outd,CO*H*W);
static float gpu_[CO*H*W];
#define CP(dst,src,n) for(int i_=0;i_<(n);i_++) dst[i_]=(double)src[i_]
int main(void){
  fill(); run_f();
  CP(xd,x_,CI*H*W); CP(td,temb_,TE); CP(g1d,g1_,CI); CP(b1d,b1_,CI); CP(w1d,w1_,CO*CI*9); CP(c1d,c1_,CO);
  CP(wtd,wt_,TE*CO); CP(btd,bt_,CO); CP(g2d,g2_,CO); CP(b2d,b2_,CO); CP(w2d,w2_,CO*CO*9); CP(c2d,c2_,CO);
  CP(wsd,ws_,CO*CI); CP(bsd,bs_,CO);
  shape_d s={CI,CO,H,W,G,TE};
  resblock_d(s,xd,td,g1d,b1d,w1d,c1d,wtd,btd,g2d,b2d,w2d,c2d,wsd,bsd,ad,h1d,tpd,bbd,h2d,skd,outd);
  if(fread(gpu_,sizeof(float),CO*H*W,stdin)!=(size_t)(CO*H*W)){fprintf(stderr,"short read\n");return 2;}
  double eg=0,ec=0,mref=0;
  for(int i=0;i<CO*H*W;i++){
    double a=fabs((double)gpu_[i]-outd[i])/fmax(1.0,fabs(outd[i])), b=fabs((double)out_[i]-outd[i])/fmax(1.0,fabs(outd[i]));
    if(!(a<=eg)) eg=isnan(a)?1e30:a; if(b>ec) ec=b; if(fabs(outd[i])>mref) mref=fabs(outd[i]);
  }
  printf("elements %d gpu_err %.3e cpu_f32_err %.3e max_abs_ref %.3e\n",CO*H*W,eg,ec,mref); return 0;
}
