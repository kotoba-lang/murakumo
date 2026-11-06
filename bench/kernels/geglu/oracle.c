/* f64 reference for geglu using libm erf; the twin is #included. */
#define NO_MAIN
#include "kernel.c"
int main(void){
  fill(); double maxabs=0,maxref=0; long cnt=0;
  for(int r=0;r<R;r++) for(int i=0;i<N;i++){
    double a=x_[r*2*N+i], g=x_[r*2*N+N+i];
    double ref=a*0.5*g*(1.0+erf(g/sqrt(2.0)));
    double e=fabs((double)out_at(r,i)-ref); if(e>maxabs)maxabs=e; if(fabs(ref)>maxref)maxref=fabs(ref); cnt++;
  }
  printf("elements %ld max_abs_err %.3e max_abs_ref %.3e\n",cnt,maxabs,maxref); return 0;}
