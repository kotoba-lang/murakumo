/* Reference for ops_metal.kotoba.in. Rebuilds the same inputs (the LCG draw & VMASK, minus VOFF, as
 * integers; counts N0, N1, N2 each rounded up to 4; A = s/16, B = 1 + s/256, C = s/64), computes the
 * operation in double with libm, reads the GPU's raw float32 output from stdin and prints the largest
 * |gpu - ref| / max(1, |ref|) and the largest |ref|. One -DOP_<name> selects the operation:
 *   OP_GN  C HW G         GroupNorm (affine, eps 1e-5) then SiLU           x=A gamma=B beta=C
 *   OP_LN  M N            LayerNorm over the last axis (affine, eps 1e-5)  x=A gamma=B beta=C
 *   OP_GEGLU M N          a * gelu(g), a = A[r][0..N), g = A[r][N..2N), exact erf
 *   OP_UP  C H W          nearest 2x upsample
 *   OP_TS  DIM NT TS      sinusoidal timestep embedding [cos | sin], t = TS*i
 *   OP_ADD N              A + C
 *   cc -O2 -DOP_GN -DC=32 -DHW=64 -DG=8 ops_ref.c -o ref -lm */
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
#define R4(n) ((((n)>4?(n):4)+3)/4*4)
static int64_t lcg(int64_t s){return (s*1103515245LL+12345LL)&2147483647LL;}
static int64_t fill(double* a,int n,int64_t s,int kind){
  for(int i=0;i<n;i++){ s=lcg(s); double v=(double)(((s>>8)&VMASK)-VOFF);
    a[i]= kind==0 ? v/16.0 : kind==1 ? 1.0+v/256.0 : v/64.0; }
  return s;
}
#if defined(OP_GN)
#define N0 (C*HW)
#define N1 C
#define N2 C
#define NOUT (C*HW)
#elif defined(OP_LN)
#define N0 (M*N)
#define N1 N
#define N2 N
#define NOUT (M*N)
#elif defined(OP_GEGLU)
#define N0 (M*2*N)
#define N1 4
#define N2 4
#define NOUT (M*N)
#elif defined(OP_UP)
#define N0 (C*H*W)
#define N1 4
#define N2 4
#define NOUT (C*4*H*W)
#elif defined(OP_TS)
#define N0 4
#define N1 4
#define N2 4
#define NOUT (NT*DIM)
#elif defined(OP_ADD)
#define N0 N
#define N1 4
#define N2 N
#define NOUT N
#endif
static double a_[R4(N0)], b_[R4(N1)], c_[R4(N2)], r_[NOUT];
static float g_[NOUT];
int main(void){
  int64_t s=12345; s=fill(a_,R4(N0),s,0); s=fill(b_,R4(N1),s,1); s=fill(c_,R4(N2),s,2);
#if defined(OP_GN)
  { int n=(C/G)*HW; for(int g=0;g<G;g++){ double sum=0; for(int i=0;i<n;i++) sum+=a_[g*n+i]; double mean=sum/n, q=0;
      for(int i=0;i<n;i++){double d=a_[g*n+i]-mean;q+=d*d;} double inv=1.0/sqrt(q/n+1e-5);
      for(int i=0;i<n;i++){ int ch=g*(C/G)+i/HW; double v=(a_[g*n+i]-mean)*inv*b_[ch]+c_[ch]; r_[g*n+i]=v/(1.0+exp(-v)); } } }
#elif defined(OP_LN)
  for(int r=0;r<M;r++){ double sum=0; for(int i=0;i<N;i++) sum+=a_[r*N+i]; double mean=sum/N,q=0;
      for(int i=0;i<N;i++){double d=a_[r*N+i]-mean;q+=d*d;} double inv=1.0/sqrt(q/N+1e-5);
      for(int i=0;i<N;i++) r_[r*N+i]=(a_[r*N+i]-mean)*inv*b_[i]+c_[i]; }
#elif defined(OP_GEGLU)
  for(int r=0;r<M;r++) for(int i=0;i<N;i++){ double a=a_[r*2*N+i], g=a_[r*2*N+N+i]; r_[r*N+i]=a*(0.5*g*(1.0+erf(g/sqrt(2.0)))); }
#elif defined(OP_UP)
  for(int c=0;c<C;c++) for(int y=0;y<2*H;y++) for(int x=0;x<2*W;x++) r_[(c*2*H+y)*2*W+x]=a_[(c*H+y/2)*W+x/2];
#elif defined(OP_TS)
  { int half=DIM/2; for(int t=0;t<NT;t++) for(int j=0;j<DIM;j++){ int k=j%half; double arg=(double)(TS*t)*exp(-log(10000.0)*k/half); r_[t*DIM+j]= j<half?cos(arg):sin(arg); } }
#elif defined(OP_ADD)
  for(int i=0;i<N;i++) r_[i]=a_[i]+c_[i];
#endif
  if(fread(g_,sizeof(float),NOUT,stdin)!=(size_t)NOUT){fprintf(stderr,"short read\n");return 2;}
  double maxerr=0,maxref=0;
  for(int i=0;i<NOUT;i++){ double e=fabs((double)g_[i]-r_[i])/fmax(1.0,fabs(r_[i])); if(!(e<=maxerr)) maxerr=isnan(e)?1e30:e; if(fabs(r_[i])>maxref)maxref=fabs(r_[i]); }
  printf("elements %d max_err %.3e max_abs_ref %.3e\n",NOUT,maxerr,maxref); return 0;
}
