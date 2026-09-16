package main

import (
    "bytes"
    "encoding/binary"
    "image"
    "image/color"
    "image/png"
    "os"
)

func set(img *image.RGBA,x,y int,c color.RGBA){if x>=0&&y>=0&&x<img.Bounds().Dx()&&y<img.Bounds().Dy(){img.SetRGBA(x,y,c)}}
func fill(img *image.RGBA,c color.RGBA){for y:=0;y<img.Bounds().Dy();y++{for x:=0;x<img.Bounds().Dx();x++{img.SetRGBA(x,y,c)}}}
func line(img *image.RGBA,x0,y0,x1,y1,w int,c color.RGBA){dx,dy:=x1-x0,y1-y0;n:=dx;if n<0{n=-n};ay:=dy;if ay<0{ay=-ay};if ay>n{n=ay};if n<1{n=1};rr:=w*w/4;for i:=0;i<=n;i++{x:=x0+dx*i/n;y:=y0+dy*i/n;for yy:=y-w;yy<=y+w;yy++{for xx:=x-w;xx<=x+w;xx++{ddx,ddy:=xx-x,yy-y;if ddx*ddx+ddy*ddy<=rr{set(img,xx,yy,c)}}}}}
func roundedRect(img *image.RGBA,x0,y0,x1,y1,r int,c color.RGBA){for y:=y0;y<y1;y++{for x:=x0;x<x1;x++{dx,dy:=0,0;if x<x0+r{dx=x0+r-x}else if x>=x1-r{dx=x-(x1-r-1)};if y<y0+r{dy=y0+r-y}else if y>=y1-r{dy=y-(y1-r-1)};if dx==0||dy==0||dx*dx+dy*dy<=r*r{set(img,x,y,c)}}}}
func poly(img *image.RGBA,pts [][2]int,c color.RGBA){minY,maxY:=9999,-1;for _,p:=range pts{if p[1]<minY{minY=p[1]};if p[1]>maxY{maxY=p[1]}};for y:=minY;y<=maxY;y++{xs:=[]int{};for i:=0;i<len(pts);i++{a,b:=pts[i],pts[(i+1)%len(pts)];if(a[1]<=y&&b[1]>y)||(b[1]<=y&&a[1]>y){x:=a[0]+(y-a[1])*(b[0]-a[0])/(b[1]-a[1]);xs=append(xs,x)}};if len(xs)>=2{if xs[0]>xs[1]{xs[0],xs[1]=xs[1],xs[0]};for x:=xs[0];x<=xs[1];x++{set(img,x,y,c)}}}}
func main(){
    const s=256
    img:=image.NewRGBA(image.Rect(0,0,s,s))
    bg:=color.RGBA{8,21,34,255};cyan:=color.RGBA{39,200,255,255};cyan2:=color.RGBA{114,223,255,255};white:=color.RGBA{248,251,255,255};pale:=color.RGBA{217,232,244,255}
    fill(img,bg);roundedRect(img,18,18,238,238,36,cyan);roundedRect(img,24,24,232,232,31,bg)
    poly(img,[][2]int{{150,70},{194,39},{211,56},{181,99},{126,154},{104,132}},cyan2);line(img,157,76,196,49,8,white)
    poly(img,[][2]int{{104,132},{126,154},{115,184},{86,205},{57,210},{73,190},{78,164}},white);line(img,105,132,126,154,5,pale)
    var pb bytes.Buffer;_=png.Encode(&pb,img);data:=pb.Bytes();f,_:=os.Create("app.ico");defer f.Close();binary.Write(f,binary.LittleEndian,uint16(0));binary.Write(f,binary.LittleEndian,uint16(1));binary.Write(f,binary.LittleEndian,uint16(1));f.Write([]byte{0,0,0,0});binary.Write(f,binary.LittleEndian,uint16(1));binary.Write(f,binary.LittleEndian,uint16(32));binary.Write(f,binary.LittleEndian,uint32(len(data)));binary.Write(f,binary.LittleEndian,uint32(22));f.Write(data)
}
