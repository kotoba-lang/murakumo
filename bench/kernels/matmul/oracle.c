/* f64 reference for matmul (products accumulated in double); the twin is #included. */
#define NO_MAIN
#include "kernel.c"
int main(void){
  fill(); double maxabs=0,maxref=0; long cnt=0;
  for(int i=0;i<M;i++) for(int j=0;j<N;j++){
    double r=bias_[j]; for(int k=0;k<K;k++) r+=(double)a_[i*K+k]*(double)b_[k*N+j];
    double e=fabs((double)at(i,j)-r); if(e>maxabs)maxabs=e; if(fabs(r)>maxref)maxref=fabs(r); cnt++;
  }
  printf("elements %ld max_abs_err %.3e max_abs_ref %.3e\n",cnt,maxabs,maxref); return 0;}
