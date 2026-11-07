/* f64 reference for attention: same tensors, double arithmetic with libm exp.
 * Reports the largest error of the f32 twin's outputs. The twin is #included. */
#define NO_MAIN
#include "attention.c"
int main(void){
  fill(); double maxabs=0,maxref=0; long cnt=0; float p[LK]; static double pd[LK];
  for(int h=0;h<NH;h++) for(int i=0;i<LQ;i++){
    probs(h,i,p);
    double m=-1e300,sum=0;
    for(int j=0;j<LK;j++){ double a=0; for(int c=0;c<D;c++) a+=(double)q_[i*HD+h*D+c]*(double)k_[j*HD+h*D+c]; pd[j]=a/sqrt((double)D); if(pd[j]>m)m=pd[j]; }
    for(int j=0;j<LK;j++){ pd[j]=exp(pd[j]-m); sum+=pd[j]; }
    for(int c=0;c<D;c++){
      double o=0; for(int j=0;j<LK;j++) o+=pd[j]/sum*(double)v_[j*HD+h*D+c];
      double e=fabs((double)out_at(p,h,c)-o); if(e>maxabs)maxabs=e; if(fabs(o)>maxref)maxref=fabs(o); cnt++;
    }
  }
  printf("elements %ld max_abs_err %.3e max_abs_ref %.3e\n",cnt,maxabs,maxref);
  return 0;}
