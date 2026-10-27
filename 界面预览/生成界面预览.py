from PIL import Image, ImageDraw, ImageFont
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUT = ROOT / '首页与历史_深浅色示意.png'
FONT = 'C:/Windows/Fonts/msyh.ttc'
BOLD = 'C:/Windows/Fonts/msyhbd.ttc'
def font(size, bold=False):
    return ImageFont.truetype(BOLD if bold else FONT, size)

im = Image.new('RGB', (1220, 1120), '#e7e9eb')
d = ImageDraw.Draw(im)
d.text((74, 32), 'X 媒体下载', fill='#101317', font=font(31, True))
d.text((74, 82), '首页与历史页 · 深浅色示意', fill='#687480', font=font(19))
d.text((835, 43), '界面方案  /  01', fill='#687480', font=font(18))

def phone(x, dark, page):
    y, w, h = 147, 475, 906
    bg = '#000000' if dark else '#ffffff'
    text = '#f4f6f8' if dark else '#0f1419'
    muted = '#7f8992' if dark else '#657786'
    card = '#15191c' if dark else '#f3f6f8'
    border = '#30363b' if dark else '#e1e6ea'
    accent = '#1d9bf0'
    d.rounded_rectangle((x-7,y-7,x+w+7,y+h+7), radius=40, fill='#c6cbd0')
    d.rounded_rectangle((x,y,x+w,y+h), radius=34, fill=bg)
    d.text((x+26,y+18), '9:41', fill=text, font=font(16,True))
    d.rectangle((x+w-52,y+23,x+w-39,y+31), outline=text, width=2)
    d.rectangle((x+w-38,y+25,x+w-36,y+29), fill=text)
    d.line((x+w-75,y+30,x+w-64,y+20), fill=text, width=3)
    d.text((x+27,y+65), 'X', fill=text, font=ImageFont.truetype('C:/Windows/Fonts/arialbd.ttf', 30))
    d.text((x+70,y+72), '媒体下载', fill=text, font=font(19,True))
    d.ellipse((x+w-54,y+70,x+w-31,y+93), outline=muted, width=2)
    if page=='home':
        d.text((x+28,y+138), '留住喜欢的内容', fill=text, font=font(30,True))
        d.text((x+28,y+188), '从 X 分享帖子，或粘贴链接开始', fill=muted, font=font(16))
        d.rounded_rectangle((x+26,y+244,x+w-26,y+304), radius=14, fill=card, outline=border, width=1)
        d.text((x+44,y+262), '粘贴 X 帖子链接', fill=muted, font=font(16))
        d.rounded_rectangle((x+26,y+318,x+w-26,y+373), radius=27, fill=accent)
        d.text((x+180,y+334), '开始下载  ↓', fill='white', font=font(18,True))
        d.rounded_rectangle((x+26,y+403,x+w-26,y+481), radius=16, fill=card)
        d.ellipse((x+43,y+426,x+78,y+461), fill='#18364a')
        d.text((x+53,y+430), '↗', fill=accent, font=font(18,True))
        d.text((x+91,y+419), '从 X 分享更快捷', fill=text, font=font(15,True))
        d.text((x+91,y+447), '点击分享 → 分享… → 媒体下载', fill=muted, font=font(13))
        d.text((x+28,y+528), '最近保存', fill=text, font=font(19,True))
        d.text((x+w-91,y+532), '全部  ›', fill=accent, font=font(14))
        for i, (shade,label,sub,tag) in enumerate([('#295068','城市里的晚霞','视频 · 1080P','▶'),('#596c75','光影与片刻','图片','▣')]):
            yy=y+574+i*93
            d.rounded_rectangle((x+27,yy,x+112,yy+72), radius=10, fill=shade)
            d.polygon([(x+28,yy+71),(x+57,yy+28),(x+76,yy+54),(x+112,yy+20),(x+112,yy+71)], fill='#83a2a7')
            if i == 0:
                d.polygon([(x+46,yy+23),(x+46,yy+49),(x+65,yy+36)], fill='white')
            else:
                d.rounded_rectangle((x+44,yy+22,x+67,yy+49),radius=3,outline='white',width=2)
            d.text((x+130,yy+5), label, fill=text, font=font(16,True))
            d.text((x+130,yy+37), sub, fill=muted, font=font(13))
    else:
        d.text((x+28,y+137), '下载记录', fill=text, font=font(30,True))
        d.text((x+28,y+187), '你的收藏，随时查看', fill=muted, font=font(16))
        d.rounded_rectangle((x+27,y+237,x+114,y+277), radius=20, fill=text)
        d.text((x+51,y+247), '全部', fill=bg, font=font(14,True))
        d.text((x+143,y+247), '图片', fill=muted, font=font(14,True))
        d.text((x+231,y+247), '视频', fill=muted, font=font(14,True))
        cells=[('#749aa6','秋日的风景','图片'),('#334c60','公路与远方','视频'),('#ba8d87','温柔一刻','GIF'),('#7785a8','夜色片段','视频')]
        for i,(shade,label,kind) in enumerate(cells):
            xx=x+27+(i%2)*214; yy=y+308+(i//2)*228
            d.rounded_rectangle((xx,yy,xx+202,yy+152),radius=14,fill=shade)
            d.polygon([(xx,yy+151),(xx+61,yy+57),(xx+100,yy+112),(xx+151,yy+34),(xx+202,yy+124),(xx+202,yy+151)], fill='#b6c7c8')
            d.rounded_rectangle((xx+12,yy+12,xx+62,yy+36),radius=12,fill='#24343d')
            d.text((xx+22,yy+15),kind,fill='white',font=font(11,True))
            d.text((xx,yy+164),label,fill=text,font=font(15,True))
            d.text((xx,yy+190),'示例记录',fill=muted,font=font(12))
    d.line((x+25,y+h-89,x+w-25,y+h-89),fill=border,width=1)
    c1 = accent if page=='home' else muted
    c2 = accent if page=='history' else muted
    d.line((x+93,y+h-59,x+102,y+h-67,x+111,y+h-59),fill=c1,width=2)
    d.rectangle((x+96,y+h-59,x+108,y+h-49),outline=c1,width=2)
    d.text((x+122,y+h-70),'首页',fill=c1,font=font(15,True))
    d.rounded_rectangle((x+282,y+h-65,x+299,y+h-49),radius=3,outline=c2,width=2)
    d.line((x+285,y+h-61,x+296,y+h-61),fill=c2,width=2)
    d.text((x+309,y+h-70),'记录',fill=c2,font=font(15,True))
    d.rounded_rectangle((x+184,y+h-15,x+291,y+h-10),radius=3,fill=muted)

phone(74,True,'home')
phone(672,False,'history')
d.text((74,1077), '示意图中的缩略图和文字均为设计占位，不代表真实下载记录。',fill='#687480',font=font(15))
im.save(OUT, optimize=True)
print(OUT)
