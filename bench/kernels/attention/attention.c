/* C twin of attention.kotoba.in: non-causal multi-head scaled dot-product
 * attention in f32, the block that makes up SpatialTransformer self- and
 * cross-attention. Q is [LQ, NH*D], K and V are [LK, NH*D]; LQ == LK is
 * self-attention, LK != LQ is cross-attention (e.g. 77 text tokens).
 * Per (head, query): scores = (q . k) * (1/sqrt(D)) summed over d in order,
 * softmax with the row maximum subtracted, probabilities p = e / sum, and the
 * output o[c] = sum_j p[j] * v[j][c] accumulated in j order. Every step rounds on
 * its own: build with -ffp-contract=off and without -ffast-math.
 *   cc -O2 -ffp-contract=off -DNH=4 -DD=16 -DLQ=64 -DLK=64 -DREPS=1 */
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <math.h>
#ifndef NH
#define NH 4
#endif
#ifndef D
#define D 16
#endif
#ifndef LQ
#define LQ 64
#endif
#ifndef LK
#define LK 64
#endif
#ifndef REPS
#define REPS 1
#endif
#define HD (NH*D)
static float F(uint32_t u){float f;memcpy(&f,&u,4);return f;}
static uint32_t B(float f){uint32_t u;memcpy(&u,&f,4);return u;}
static int64_t lcg(int64_t s){return (s*1103515245LL+12345LL)&2147483647LL;}
static float expf_k(float x){
  float lim=F(0x42AE0000u);
  x=fminf(x,lim); x=fmaxf(x,-lim);
  float fx=x*F(0x3FB8AA3Bu); fx=fx+F(0x4B400000u);
  int32_t k=(int32_t)(B(fx)&0x7FFFFFu)-0x400000;
  float kf=fx-F(0x4B400000u);
  float r=x-kf*F(0x3F318000u); r=r-kf*F(0xB95E8083u);
  float y=F(0x39506967u);
  y=y*r; y=y+F(0x3AB743CEu);
  y=y*r; y=y+F(0x3C088908u);
  y=y*r; y=y+F(0x3D2AA9C1u);
  y=y*r; y=y+F(0x3E2AAAAAu);
  y=y*r; y=y+F(0x3F000000u);
  float p=y*r; p=p*r; p=p+r; p=p+F(0x3F800000u);
  return p*F((uint32_t)(k+127)<<23);
}
static float q_[LQ*HD], k_[LK*HD], v_[LK*HD];
static int64_t fill(void){
  int64_t s=12345;
  float* t[3]={q_,k_,v_}; int n[3]={LQ*HD,LK*HD,LK*HD};
  for(int a=0;a<3;a++) for(int i=0;i<n[a];i++){s=lcg(s);int64_t v=((s>>8)&65535)-32768;t[a][i]=(float)v*F(a<2?0x39800000u:0x38800000u);} /* q,k 2^-12; v 2^-14 */
  return s;
}
/* writes the probabilities of query i, head h into p[LK] */
static void probs(int h,int i,float* p){
  float scale=F(0x3F800000u)/sqrtf((float)D);
  float m=0;
  for(int j=0;j<LK;j++){
    float a=0; for(int c=0;c<D;c++){ float t=q_[i*HD+h*D+c]*k_[j*HD+h*D+c]; a=a+t; }
    p[j]=a*scale; if(j==0||p[j]>m) m=p[j];
  }
  float sum=0;
  for(int j=0;j<LK;j++){ float e=expf_k(p[j]-m); p[j]=e; sum=sum+e; }
  for(int j=0;j<LK;j++) p[j]=p[j]/sum;
}
static float out_at(const float* p,int h,int c){
  float o=0; for(int j=0;j<LK;j++){ float t=p[j]*v_[j*HD+h*D+c]; o=o+t; } return o;
}
#ifndef NO_MAIN
int main(void){
  int64_t c=fill()&281474976710655LL; float p[LK];
  for(int rep=0;rep<REPS;rep++)
    for(int h=0;h<NH;h++) for(int i=0;i<LQ;i++){
      probs(h,i,p);
      for(int cc=0;cc<D;cc++) c=(c*31+(int64_t)B(out_at(p,h,cc)))&281474976710655LL;
    }
  printf("%lld\n",(long long)c);
  return 0;
}
#endif
