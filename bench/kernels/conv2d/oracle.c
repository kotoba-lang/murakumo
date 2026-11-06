/* f64-accumulated reference for conv2d; the twin is #included. */
#define NO_MAIN
#include "kernel.c"
int main(void){
  fill(); double maxabs=0,maxref=0; long cnt=0;
  for(int co=0;co<CO;co++) for(int y=0;y<OH;y++) for(int x=0;x<OW;x++){
    double r=b_[co];
    for(int ci=0;ci<CI;ci++) for(int ky=0;ky<KS;ky++) for(int kx=0;kx<KS;kx++){
      int yy=y*STRIDE+ky-PAD, xx=x*STRIDE+kx-PAD; if(yy<0||yy>=H||xx<0||xx>=W) continue;
      r+=(double)w_[((co*CI+ci)*KS+ky)*KS+kx]*(double)x_[(ci*H+yy)*W+xx];
    }
    double e=fabs((double)conv_at(co,y,x)-r); if(e>maxabs)maxabs=e; if(fabs(r)>maxref)maxref=fabs(r); cnt++;
  }
  printf("elements %ld max_abs_err %.3e max_abs_ref %.3e\n",cnt,maxabs,maxref); return 0;}
