/* Reference for conv_epilogue_metal.kotoba.in. Rebuilds the same inputs (the LCG draw & VMASK,
 * minus VOFF, as integers: X, W, bias with its count rounded up to 4, residual likewise), computes
 * the convolution exactly in int64 (window KS, stride STR, padding KS/2, NCHW, batch 1), applies the
 * epilogue in double (mode 0 none, 1 + bias, 2 + bias + residual, 3 SiLU(conv + bias)), reads the
 * GPU's raw float32 output (COUT rows of NPP columns) from stdin and prints the largest error
 * |gpu - reference| / max(1, |reference|) and the largest |reference|.
 *   cc -O2 -DCIN=32 -DCOUT=64 -DH=32 -DW=32 -DKS=3 -DSTR=1 -DMODE=0 conv_ref.c -o ref -lm */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#ifndef VMASK
#define VMASK 127
#endif
#ifndef VOFF
#define VOFF 64
#endif
#define PADV (KS/2)
#define OH ((H+2*PADV-KS)/STR+1)
#define OW ((W+2*PADV-KS)/STR+1)
#define NP (OH*OW)
#define NPP (((NP+63)/64)*64)
#define KD (CIN*KS*KS)
#define R4(n) (((n)+3)/4*4)
static int64_t lcg(int64_t s){return (s*1103515245LL+12345LL)&2147483647LL;}
static int16_t x_[CIN*H*W], w_[COUT*KD], b_[R4(COUT)], r_[R4(COUT*NP)];
static float g_[COUT*NPP];
static int64_t fill(int16_t* a,int n,int64_t s){for(int i=0;i<n;i++){s=lcg(s);a[i]=(int16_t)(((s>>8)&VMASK)-VOFF);}return s;}
int main(void){
  int64_t s=12345; s=fill(x_,CIN*H*W,s); s=fill(w_,COUT*KD,s); s=fill(b_,R4(COUT),s); s=fill(r_,R4(COUT*NP),s);
  if(fread(g_,sizeof(float),(size_t)COUT*NPP,stdin)!=(size_t)COUT*NPP){fprintf(stderr,"short read\n");return 2;}
  double maxerr=0,maxref=0;
  for(int co=0;co<COUT;co++) for(int oy=0;oy<OH;oy++) for(int ox=0;ox<OW;ox++){
    int64_t acc=0;
    for(int ci=0;ci<CIN;ci++) for(int ky=0;ky<KS;ky++) for(int kx=0;kx<KS;kx++){
      int yy=oy*STR+ky-PADV, xx=ox*STR+kx-PADV; if(yy<0||yy>=H||xx<0||xx>=W) continue;
      acc+=(int64_t)w_[((co*CIN+ci)*KS+ky)*KS+kx]*x_[(ci*H+yy)*W+xx];
    }
    int p=oy*OW+ox; double v=(double)acc;
    if(MODE>=1) v+=b_[co];
    if(MODE==2) v+=r_[co*NP+p];
    if(MODE==3) v=v/(1.0+exp(-v));
    double e=fabs((double)g_[co*NPP+p]-v)/fmax(1.0,fabs(v)); if(!(e<=maxerr)) maxerr=isnan(e)?1e30:e;
    if(fabs(v)>maxref) maxref=fabs(v);
  }
  printf("elements %d max_err %.3e max_abs_ref %.3e\n",COUT*NP,maxerr,maxref); return 0;
}
