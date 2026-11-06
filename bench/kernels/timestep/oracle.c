/* f64 reference for the timestep embedding: libm sin and cos of the same f32
 * arguments (so only the reduction and polynomials are being checked). */
#define NO_MAIN
#include "kernel.c"
int main(void){
  double maxabs=0; long cnt=0;
  for(int ti=0;ti<NT;ti++) for(int k=0;k<HALF;k++){
    float a=arg_at(ti,k);
    double ec=fabs((double)cos_k(a)-cos((double)a)), es=fabs((double)sin_k(a)-sin((double)a));
    if(ec>maxabs)maxabs=ec; if(es>maxabs)maxabs=es; cnt+=2;
  }
  printf("elements %ld max_abs_err %.3e\n",cnt,maxabs); return 0;}
