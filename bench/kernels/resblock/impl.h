/* The ResBlock pipeline, written once over REAL so the same code is the f32
 * twin (REAL = float, SUF = _f, exp = expf_k) and the f64 reference (REAL =
 * double, SUF = _d, exp = libm). Include with REAL, SUF and IS_DOUBLE defined.
 * Every operation is one step in an order the Kotoba program repeats. */
#define CATX(a,b) a##b
#define CAT(a,b) CATX(a,b)
#define S(n) CAT(n,SUF)
static REAL S(rexp)(REAL x){
#if IS_DOUBLE
  return exp(x);
#else
  return expf_k(x);
#endif
}
static REAL S(rsqrt)(REAL x){
#if IS_DOUBLE
  return sqrt(x);
#else
  return sqrtf(x);
#endif
}
/* GroupNorm (affine) then SiLU: x,y are [C,HW]; G groups */
static void S(gn_silu)(const REAL* x,REAL* y,int Cn,int HW,int Gn,const REAL* ga,const REAL* be){
  int n=(Cn/Gn)*HW; REAL one=1, eps=(REAL)(float)1e-5, invn=one/(REAL)n;
  for(int g=0;g<Gn;g++){
    const REAL* xb=x+g*n; REAL sum=0; for(int i=0;i<n;i++) sum=sum+xb[i];
    REAL mean=sum*invn, sq=0; for(int i=0;i<n;i++){REAL d=xb[i]-mean; sq=sq+d*d;}
    REAL var=sq*invn, inv=one/S(rsqrt)(var+eps);
    for(int i=0;i<n;i++){
      int ch=g*(Cn/Gn)+i/HW;
      REAL v=(xb[i]-mean)*inv; v=v*ga[ch]; v=v+be[ch];
      REAL e=S(rexp)(-v); e=e+one; y[g*n+i]=v/e;
    }
  }
}
static REAL S(silu)(REAL v){ REAL e=S(rexp)(-v); e=e+1; return v/e; }
/* stride-1 conv, padding ks/2, NCHW, with bias */
static void S(conv)(const REAL* x,const REAL* w,const REAL* b,REAL* out,int ci,int co,int hh,int ww,int ks){
  int pad=ks/2;
  for(int c=0;c<co;c++) for(int oy=0;oy<hh;oy++) for(int ox=0;ox<ww;ox++){
    REAL acc=b[c];
    for(int i=0;i<ci;i++) for(int ky=0;ky<ks;ky++) for(int kx=0;kx<ks;kx++){
      int yy=oy+ky-pad, xx=ox+kx-pad; if(yy<0||yy>=hh||xx<0||xx>=ww) continue;
      REAL p=w[((c*ci+i)*ks+ky)*ks+kx]*x[(i*hh+yy)*ww+xx]; acc=acc+p;
    }
    out[(c*hh+oy)*ww+ox]=acc;
  }
}
/* tp[c] = bt[c] + sum_k silu(temb[k]) * wt[k*co+c] */
static void S(temb_proj)(const REAL* temb,const REAL* wt,const REAL* bt,REAL* tp,int te,int co){
  for(int c=0;c<co;c++){
    REAL acc=bt[c];
    for(int k=0;k<te;k++){ REAL p=S(silu)(temb[k])*wt[k*co+c]; acc=acc+p; }
    tp[c]=acc;
  }
}
typedef struct { int ci,co,h,w,g,te; } S(shape);
/* x [CI,H,W], temb [TE]; parameter pointers in the order the fill draws them.
 * out [CO,H,W]. Scratch buffers are caller-provided. */
static void S(resblock)(S(shape) s,const REAL* x,const REAL* temb,
    const REAL* g1,const REAL* b1,const REAL* w1,const REAL* c1,
    const REAL* wt,const REAL* bt,
    const REAL* g2,const REAL* b2,const REAL* w2,const REAL* c2,
    const REAL* ws,const REAL* bs,
    REAL* a,REAL* h1,REAL* tp,REAL* bb,REAL* h2,REAL* sk,REAL* out){
  int HW=s.h*s.w;
  S(gn_silu)(x,a,s.ci,HW,s.g,g1,b1);
  S(conv)(a,w1,c1,h1,s.ci,s.co,s.h,s.w,3);
  S(temb_proj)(temb,wt,bt,tp,s.te,s.co);
  for(int c=0;c<s.co;c++) for(int p=0;p<HW;p++) h1[c*HW+p]=h1[c*HW+p]+tp[c];
  S(gn_silu)(h1,bb,s.co,HW,s.g,g2,b2);
  S(conv)(bb,w2,c2,h2,s.co,s.co,s.h,s.w,3);
  S(conv)(x,ws,bs,sk,s.ci,s.co,s.h,s.w,1);
  for(int i=0;i<s.co*HW;i++) out[i]=sk[i]+h2[i];
}
