from PIL import Image, ImageDraw

S=256
k=S/108.0
def pts(seq): return [(round(x*k),round(y*k)) for x,y in seq]
img=Image.new("RGBA",(S,S),(0,0,0,0))
d=ImageDraw.Draw(img)
box=[round(v*k) for v in (8,8,100,100)]
d.rounded_rectangle(box,radius=round(11*k),fill="#081522",outline="#27C8FF",width=round(4*k))
stylus=[(67,28),(81,19),(88,26),(78,40),(55,63),(45,53)]
d.polygon(pts(stylus),fill="#72DFFF")
d.line(pts(stylus+[stylus[0]]),fill="#C9F5FF",width=max(2,round(2.3*k)),joint="curve")
brush=[(45,53),(38,54),(33,59),(29,68),(27,74),(22,78),(17,80),(23,86),(34,88),(43,84),(50,80),(55,72),(55,63)]
d.polygon(pts(brush),fill="#F8FBFF")
d.line(pts(brush+[brush[0]]),fill="#D9E8F4",width=max(2,round(2*k)),joint="curve")
highlight=[(70,31),(80,24),(83,27),(75,37),(58,54),(55,51)]
d.polygon(pts(highlight),fill=(255,255,255,190))
img.save("operator/icon.png")
img.save("operator/icon.ico",sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])
print("icons generated")
