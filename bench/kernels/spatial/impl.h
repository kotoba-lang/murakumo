/* The SpatialTransformer block (one BasicTransformerBlock with linear proj_in /
 * proj_out, as in SDXL), written once over REAL: the f32 twin (REAL = float,
 * SUF = _f, exp = expf_k, erf = Abramowitz-Stegun) and the f64 reference (REAL =
 * double, SUF = _d, libm exp/erf). Include with REAL, SUF, IS_DOUBLE defined.
 *   t0 = proj_in(tokens(groupnorm(x)))
 *   t1 = t0 + attn_self(LN1(t0))       t2 = t1 + attn_cross(LN2(t1), ctx)
 *   t3 = t2 + FF(LN3(t2))  with FF = linear(geglu(linear(.)))
 *   out = x + untokens(proj_out(t3))
 * Q/K/V projections have no bias (a zero bias vector stands in: 0 + p == p). */
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
static REAL S(rerf)(REAL x){
#if IS_DOUBLE
  return erf(x);
#else
  float ax=fabsf(x);
  float t=F(0x3F800000u)/(F(0x3F800000u)+F(0x3EA7BA05u)*ax);
  float p=F(0x3F87DC22u);
  p=p*t; p=p+F(0xBFBA00E3u);
  p=p*t; p=p+F(0x3FB5F0E3u);
  p=p*t; p=p+F(0xBE91A98Eu);
  p=p*t; p=p+F(0x3E827906u);
  p=p*t;
  float e=expf_k(-(ax*ax));
  float r=F(0x3F800000u)-p*e;
  return x<0 ? -r : r;
#endif
}
/* GroupNorm (affine), no activation. x,y: [C,HW] */
static void S(gn)(const REAL* x,REAL* y,int Cn,int HW,int Gn,const REAL* ga,const REAL* be){
  int n=(Cn/Gn)*HW; REAL one=1, eps=(REAL)(float)GNEPS, invn=one/(REAL)n;
  for(int g=0;g<Gn;g++){
    const REAL* xb=x+g*n; REAL sum=0; for(int i=0;i<n;i++) sum=sum+xb[i];
    REAL mean=sum*invn, sq=0; for(int i=0;i<n;i++){REAL d=xb[i]-mean; sq=sq+d*d;}
    REAL var=sq*invn, inv=one/S(rsqrt)(var+eps);
    for(int i=0;i<n;i++){
      int ch=g*(Cn/Gn)+i/HW;
      REAL v=(xb[i]-mean)*inv; v=v*ga[ch]; y[g*n+i]=v+be[ch];
    }
  }
}
/* out[m,n] = bias[n] + sum_k x[m,k]*w[k,n] */
static void S(linear)(const REAL* x,const REAL* w,const REAL* b,REAL* out,int M,int K,int N){
  for(int i=0;i<M;i++) for(int j=0;j<N;j++){
    REAL acc=b[j]; for(int k=0;k<K;k++){ REAL p=x[i*K+k]*w[k*N+j]; acc=acc+p; } out[i*N+j]=acc;
  }
}
static void S(layernorm)(const REAL* x,REAL* out,const REAL* ga,const REAL* be,int M,int N){
  REAL one=1, eps=(REAL)(float)1e-5, invn=one/(REAL)N;
  for(int r=0;r<M;r++){
    const REAL* xr=x+r*N; REAL sum=0; for(int i=0;i<N;i++) sum=sum+xr[i];
    REAL mean=sum*invn, sq=0; for(int i=0;i<N;i++){REAL d=xr[i]-mean; sq=sq+d*d;}
    REAL var=sq*invn, inv=one/S(rsqrt)(var+eps);
    for(int i=0;i<N;i++){ REAL y=(xr[i]-mean)*inv; y=y*ga[i]; out[r*N+i]=y+be[i]; }
  }
}
/* non-causal multi-head attention; q [LQ,NH*D], k,v [LK,NH*D]; sc is LK scratch */
static void S(attention)(const REAL* q,const REAL* k,const REAL* v,REAL* out,REAL* sc,int NHn,int Dn,int LQn,int LKn){
  int HDn=NHn*Dn; REAL one=1; REAL scale=one/S(rsqrt)((REAL)Dn);
  for(int h=0;h<NHn;h++) for(int i=0;i<LQn;i++){
    REAL m=0;
    for(int j=0;j<LKn;j++){
      REAL a=0; for(int c=0;c<Dn;c++){ REAL t=q[i*HDn+h*Dn+c]*k[j*HDn+h*Dn+c]; a=a+t; }
      sc[j]=a*scale; if(j==0||sc[j]>m) m=sc[j];
    }
    REAL sum=0; for(int j=0;j<LKn;j++){ REAL e=S(rexp)(sc[j]-m); sc[j]=e; sum=sum+e; }
    for(int j=0;j<LKn;j++) sc[j]=sc[j]/sum;
    for(int c=0;c<Dn;c++){ REAL o=0; for(int j=0;j<LKn;j++){ REAL t=sc[j]*v[j*HDn+h*Dn+c]; o=o+t; } out[i*HDn+h*Dn+c]=o; }
  }
}
/* x [M,2N] -> out [M,N] = a * gelu(g) */
static void S(geglu)(const REAL* x,REAL* out,int M,int N){
  for(int r=0;r<M;r++) for(int i=0;i<N;i++){
    REAL a=x[r*2*N+i], g=x[r*2*N+N+i];
    REAL e=S(rerf)(g*(REAL)(float)0.70710678118654752);
    REAL gelu=((REAL)0.5*g)*((REAL)1+e); out[r*N+i]=a*gelu;
  }
}
typedef struct { int c,h,w,g,nh,lc,cc,fi; } S(shape);
/* parameters (all REAL*): see the field order in kernel.c's fill() */
typedef struct {
  const REAL *gng,*gnb,*wi,*bi, *l1g,*l1b,*wq1,*wk1,*wv1,*wo1,*bo1,
             *l2g,*l2b,*wq2,*wk2,*wv2,*wo2,*bo2, *l3g,*l3b,*wf1,*bf1,*wf2,*bf2, *wout,*bout, *zero;
} S(params);
typedef struct { REAL *gn,*tok,*t0,*ln,*q,*k,*v,*at,*o,*t1,*t2,*ff,*gg,*f2,*t3,*p,*sc; } S(scratch);
static void S(spatial)(S(shape) s,S(params) P,const REAL* x,const REAL* ctx,S(scratch) W,REAL* out){
  int C=s.c, HW=s.h*s.w, D=C/s.nh;
  S(gn)(x,W.gn,C,HW,s.g,P.gng,P.gnb);
  for(int p=0;p<HW;p++) for(int c=0;c<C;c++) W.tok[p*C+c]=W.gn[c*HW+p];
  S(linear)(W.tok,P.wi,P.bi,W.t0,HW,C,C);
  /* self-attention */
  S(layernorm)(W.t0,W.ln,P.l1g,P.l1b,HW,C);
  S(linear)(W.ln,P.wq1,P.zero,W.q,HW,C,C); S(linear)(W.ln,P.wk1,P.zero,W.k,HW,C,C); S(linear)(W.ln,P.wv1,P.zero,W.v,HW,C,C);
  S(attention)(W.q,W.k,W.v,W.at,W.sc,s.nh,D,HW,HW);
  S(linear)(W.at,P.wo1,P.bo1,W.o,HW,C,C);
  for(int i=0;i<HW*C;i++) W.t1[i]=W.t0[i]+W.o[i];
  /* cross-attention over ctx [LC,CC] */
  S(layernorm)(W.t1,W.ln,P.l2g,P.l2b,HW,C);
  S(linear)(W.ln,P.wq2,P.zero,W.q,HW,C,C); S(linear)(ctx,P.wk2,P.zero,W.k,s.lc,s.cc,C); S(linear)(ctx,P.wv2,P.zero,W.v,s.lc,s.cc,C);
  S(attention)(W.q,W.k,W.v,W.at,W.sc,s.nh,D,HW,s.lc);
  S(linear)(W.at,P.wo2,P.bo2,W.o,HW,C,C);
  for(int i=0;i<HW*C;i++) W.t2[i]=W.t1[i]+W.o[i];
  /* feed-forward */
  S(layernorm)(W.t2,W.ln,P.l3g,P.l3b,HW,C);
  S(linear)(W.ln,P.wf1,P.bf1,W.ff,HW,C,2*s.fi);
  S(geglu)(W.ff,W.gg,HW,s.fi);
  S(linear)(W.gg,P.wf2,P.bf2,W.f2,HW,s.fi,C);
  for(int i=0;i<HW*C;i++) W.t3[i]=W.t2[i]+W.f2[i];
  S(linear)(W.t3,P.wout,P.bout,W.p,HW,C,C);
  for(int c=0;c<C;c++) for(int p=0;p<HW;p++) out[c*HW+p]=x[c*HW+p]+W.p[p*C+c];
}
