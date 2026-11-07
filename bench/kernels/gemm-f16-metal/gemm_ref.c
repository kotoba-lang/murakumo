/* Double-precision reference for gemm_f16_metal.kotoba.in. Rebuilds A and B (15-bit integers s, the LCG
 * draw & VMASK minus VOFF, scaled by 2^-14), computes C = A.B in double two ways, reads the GPU's raw float32
 * C from stdin and prints the largest normalised error |gpu - ref| / max(1, |ref|) against each:
 *   exact   the unrounded values s / 2^14           (f16 storage rounding + float32 accumulation)
 *   half    the values rounded to half first        (float32 accumulation order only)
 *   cc -O2 -DM=512 -DK=512 -DN=640 gemm_ref.c -o ref -lm   (needs a compiler with _Float16, e.g. Apple clang) */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#ifndef M
#define M 512
#endif
#ifndef K
#define K 512
#endif
#ifndef N
#define N 640
#endif
#ifndef VMASK
#define VMASK 32767
#endif
#ifndef VOFF
#define VOFF 16384
#endif
static int64_t lcg(int64_t s){return (s*1103515245LL+12345LL)&2147483647LL;}
static double ae_[M*K], aq_[M*K], be_[K*N], bq_[K*N];
static float g_[M*N];
int main(void){
  int64_t s=12345; double sc=1.0/16384.0;
  for(int i=0;i<M*K;i++){s=lcg(s);int v=(int)(((s>>8)&VMASK)-VOFF);ae_[i]=v*sc;aq_[i]=(double)(_Float16)(float)v*sc;}
  for(int i=0;i<K*N;i++){s=lcg(s);int v=(int)(((s>>8)&VMASK)-VOFF);be_[i]=v*sc;bq_[i]=(double)(_Float16)(float)v*sc;}
  if(fread(g_,sizeof(float),(size_t)M*N,stdin)!=(size_t)M*N){fprintf(stderr,"short read\n");return 2;}
  double ee=0,eq=0,mref=0;
  for(int i=0;i<M;i++) for(int j=0;j<N;j++){
    double re=0,rq=0; for(int k=0;k<K;k++){re+=ae_[i*K+k]*be_[k*N+j];rq+=aq_[i*K+k]*bq_[k*N+j];}
    double g=(double)g_[i*N+j];
    double e1=fabs(g-re)/fmax(1.0,fabs(re)), e2=fabs(g-rq)/fmax(1.0,fabs(rq));
    if(!(e1<=ee)) ee=isnan(e1)?1e30:e1; if(!(e2<=eq)) eq=isnan(e2)?1e30:e2; if(fabs(re)>mref)mref=fabs(re);
  }
  printf("elements %d err_exact %.3e err_half %.3e max_abs_ref %.3e\n",M*N,ee,eq,mref); return 0;
}
