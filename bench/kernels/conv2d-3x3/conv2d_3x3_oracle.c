/* f64 reference for conv2d_3x3: same tensors, double accumulation (taps summed
 * in f64, so the f32 twin's rounding error shows against it). Reports the
 * largest error of the f32 twin over all outputs. The twin is #included so the
 * two cannot drift.  cc -O2 -ffp-contract=off -DNO_MAIN ... */
#define NO_MAIN
#include "conv2d_3x3.c"
int main(void){
  fill(); double maxabs=0, maxref=0; long cnt=0;
  for(int co=0;co<CO;co++) for(int y=0;y<H;y++) for(int x=0;x<W;x++){
    double r=b_[co];
    for(int ci=0;ci<CI;ci++) for(int ky=0;ky<3;ky++) for(int kx=0;kx<3;kx++){
      int yy=y+ky-1, xx=x+kx-1; if(yy<0||yy>=H||xx<0||xx>=W) continue;
      r+=(double)w_[(co*CI+ci)*9+ky*3+kx]*(double)x_[(ci*H+yy)*W+xx];
    }
    double e=(double)conv_at(co,y,x)-r; if(e<0)e=-e; if(e>maxabs)maxabs=e;
    double a=r<0?-r:r; if(a>maxref)maxref=a; cnt++;
  }
  printf("elements %ld max_abs_err %.3e max_abs_ref %.3e\n",cnt,maxabs,maxref);
  return 0;}
