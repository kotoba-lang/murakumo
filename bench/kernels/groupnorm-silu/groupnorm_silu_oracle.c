/* f64 reference for groupnorm_silu: same synthetic tensor, computed in double
 * with libm exp. Reports the largest error of the f32 twin's outputs against it.
 * The f32 path is groupnorm_silu.c's, copied by #include so the two cannot drift.
 *   cc -O2 -ffp-contract=off -DC=320 -DHW=1024 -DG=32 -o oracle groupnorm_silu_oracle.c -lm */
#define main twin_main
#include "groupnorm_silu.c"
#undef main
int main(void){
  int n=(C/G)*HW; double maxabs=0, maxrel=0; long cnt=0;
  fill();
  static double xd[C*HW];
  for(int i=0;i<C*HW;i++) xd[i]=x_[i];
  float eps=(float)1e-5, inv_n=F(0x3F800000u)/(float)n;
  for(int g=0;g<G;g++){
    int base=g*(C/G)*HW;
    /* f32 twin statistics */
    float sum=0; for(int i=0;i<n;i++) sum=sum+x_[base+i];
    float mean=sum*inv_n; float sq=0;
    for(int i=0;i<n;i++){float d=x_[base+i]-mean; sq=sq+d*d;}
    float var=sq*inv_n; float inv=F(0x3F800000u)/sqrtf(var+eps);
    /* f64 reference statistics */
    double ds=0; for(int i=0;i<n;i++) ds+=xd[base+i];
    double dm=ds/n, dq=0; for(int i=0;i<n;i++){double d=xd[base+i]-dm; dq+=d*d;}
    double dinv=1.0/sqrt(dq/n+(double)eps);
    for(int i=0;i<n;i++){
      int ch=g*(C/G)+i/HW;
      float y=(x_[base+i]-mean)*inv; y=y*g_[ch]; y=y+b_[ch];
      float e=expf_k(-y); e=e+F(0x3F800000u); float o=y/e;
      double yd=(xd[base+i]-dm)*dinv*(double)g_[ch]+(double)b_[ch];
      double od=yd/(1.0+exp(-yd));
      double ea=fabs((double)o-od); double er=ea/fmax(fabs(od),0.1);
      if(ea>maxabs)maxabs=ea; if(er>maxrel)maxrel=er; cnt++;
    }
  }
  printf("elements %ld max_abs_err %.3e max_rel_err(|ref|>=0.1 floor) %.3e\n",cnt,maxabs,maxrel);
  return 0;}
