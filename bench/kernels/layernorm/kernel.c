/* C twin of kernel.kotoba.in: LayerNorm over the last axis with affine, f32.
 * x is [R, N]; per row mean, biased variance, y = (x-mean)/sqrt(var+eps)*g+b,
 * each step rounded alone, sums in index order.
 *   cc -O2 -ffp-contract=off -I.. -DR=64 -DN=320 -DREPS=1 */
#include "../kern.h"
#ifndef R
#define R 64
#endif
#ifndef N
#define N 320
#endif
#ifndef REPS
#define REPS 1
#endif
static float x_[R*N], g_[N], b_[N];
static int64_t fill(void){
  int64_t s=12345;
  s=fill16(x_,R*N,s,0x39000000u,0); s=fill16(g_,N,s,0x37800000u,0x3F800000u); s=fill16(b_,N,s,0x38800000u,0);
  return s;
}
static float row_stats(int r,float* inv){
  float invn=F(0x3F800000u)/(float)N, eps=(float)1e-5;
  float sum=0; for(int i=0;i<N;i++) sum=sum+x_[r*N+i];
  float mean=sum*invn; float sq=0;
  for(int i=0;i<N;i++){float d=x_[r*N+i]-mean; sq=sq+d*d;}
  float var=sq*invn; *inv=F(0x3F800000u)/sqrtf(var+eps); return mean;
}
static float out_at(int r,int i,float mean,float inv){
  float y=(x_[r*N+i]-mean)*inv; y=y*g_[i]; return y+b_[i];
}
#ifndef NO_MAIN
int main(void){
  int64_t c=fill()&MASK48;
  for(int rep=0;rep<REPS;rep++) for(int r=0;r<R;r++){
    float inv; float mean=row_stats(r,&inv);
    for(int i=0;i<N;i++) c=(c*31+(int64_t)B(out_at(r,i,mean,inv)))&MASK48;
  }
  printf("%lld\n",(long long)c); return 0;
}
#endif
