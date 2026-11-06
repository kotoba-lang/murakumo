#include <stdio.h>
#include <string.h>
#include <stdint.h>
static void p(const char*n,float f){uint32_t u;memcpy(&u,&f,4);printf("%-8s 0x%08X %u\n",n,u,u);}
int main(void){
 p("log2e",1.44269504088896341f);p("ln2hi",0.693359375f);p("ln2lo",-2.12194440e-4f);
 p("c0",1.9875691500E-4f);p("c1",1.3981999507E-3f);p("c2",8.3334519073E-3f);
 p("c3",4.1665795894E-2f);p("c4",1.6666665459E-1f);p("c5",5.0000001201E-1f);
 p("magic",12582912.0f);p("one",1.0f);p("lim",87.0f);p("nlim",-87.0f);p("eps",(float)1e-5);
 p("2^-13",1.0f/8192);p("2^-14",1.0f/16384);p("2^-10",1.0f/1024);p("half",0.5f);
 return 0;}
