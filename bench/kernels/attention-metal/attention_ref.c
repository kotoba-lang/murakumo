/* f64 reference for attention_metal.kotoba.in. Rebuilds the same inputs (the LCG draw & 127,
 * minus 64, as shorts; Q and K times 1/32, V times 1/64: exact in half), computes non-causal
 * multi-head attention over the first LK keys in double with libm exp, reads the GPU's raw
 * float32 output from stdin and prints the largest absolute error and the largest |reference|.
 *   cc -O2 -DLQ=256 -DLK=256 -DNH=4 attention_ref.c -o ref -lm */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#ifndef LQ
#define LQ 256
#endif
#ifndef LK
#define LK 256
#endif
#ifndef NH
#define NH 4
#endif
#define D 64
#define HD (NH*D)
#define LKP (((LK+31)/32)*32)
static int64_t lcg(int64_t s){return (s*1103515245LL+12345LL)&2147483647LL;}
static double q_[LQ*HD], k_[LKP*HD], v_[LKP*HD];
static float o_[LQ*HD];
int main(void){
  int64_t s=12345;
  for(int i=0;i<LQ*HD;i++){s=lcg(s);q_[i]=(double)(((s>>8)&127)-64)/32.0;}
  for(int i=0;i<LKP*HD;i++){s=lcg(s);k_[i]=(double)(((s>>8)&127)-64)/32.0;}
  for(int i=0;i<LKP*HD;i++){s=lcg(s);v_[i]=(double)(((s>>8)&127)-64)/64.0;}
  if(fread(o_,sizeof(float),(size_t)LQ*HD,stdin)!=(size_t)LQ*HD){fprintf(stderr,"short read\n");return 2;}
  double maxerr=0,maxref=0; static double p[LKP];
  for(int h=0;h<NH;h++) for(int i=0;i<LQ;i++){
    double m=-1e300;
    for(int j=0;j<LK;j++){double a=0; for(int c=0;c<D;c++) a+=q_[i*HD+h*D+c]*k_[j*HD+h*D+c]; p[j]=a/8.0; if(p[j]>m)m=p[j];}
    double sum=0; for(int j=0;j<LK;j++){p[j]=exp(p[j]-m); sum+=p[j];}
    for(int c=0;c<D;c++){
      double o=0; for(int j=0;j<LK;j++) o+=p[j]/sum*v_[j*HD+h*D+c];
      double e=fabs((double)o_[i*HD+h*D+c]-o); if(!(e<=maxerr)) maxerr=isnan(e)?1e30:e; if(fabs(o)>maxref)maxref=fabs(o);
    }
  }
  printf("elements %d max_abs_err %.3e max_abs_ref %.3e\n",LQ*HD,maxerr,maxref); return 0;
}
