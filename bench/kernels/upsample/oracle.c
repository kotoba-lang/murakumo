/* Reference for upsample: each output pixel, by its own definition (the source
 * pixel whose 2x2 block contains it), compared bit for bit with the twin. */
#define NO_MAIN
#include "kernel.c"
int main(void){
  fill(); double maxabs=0; long cnt=0;
  for(int c=0;c<C;c++) for(int sy=0;sy<H;sy++) for(int sx=0;sx<W;sx++)
    for(int dy=0;dy<2;dy++) for(int dx=0;dx<2;dx++){
      double e=fabs((double)out_at(c,2*sy+dy,2*sx+dx)-(double)x_[(c*H+sy)*W+sx]); if(e>maxabs)maxabs=e; cnt++;
    }
  printf("elements %ld max_abs_err %.3e\n",cnt,maxabs); return 0;}
