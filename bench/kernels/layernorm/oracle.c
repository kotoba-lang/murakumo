/* f64 reference for layernorm; the twin is #included so they cannot drift. */
#define NO_MAIN
#include "kernel.c"
int main(void){
  fill(); double maxabs=0,maxref=0; long cnt=0;
  for(int r=0;r<R;r++){
    float inv; float mean=row_stats(r,&inv);
    double s=0; for(int i=0;i<N;i++) s+=x_[r*N+i];
    double m=s/N,q=0; for(int i=0;i<N;i++){double d=x_[r*N+i]-m;q+=d*d;}
    double di=1.0/sqrt(q/N+(double)(float)1e-5);
    for(int i=0;i<N;i++){
      double ref=(x_[r*N+i]-m)*di*(double)g_[i]+(double)b_[i];
      double e=fabs((double)out_at(r,i,mean,inv)-ref); if(e>maxabs)maxabs=e; if(fabs(ref)>maxref)maxref=fabs(ref); cnt++;
    }
  }
  printf("elements %ld max_abs_err %.3e max_abs_ref %.3e\n",cnt,maxabs,maxref); return 0;}
