#!/usr/bin/env python3
"""Rasterize the supported offscreen scene subset. Requires Pillow; no vehicle output.

This is a software test compositor, not an Apple-compatible UI renderer.
"""
from __future__ import annotations
import argparse
import json
import math
from pathlib import Path, PurePosixPath
import time
from PIL import Image, ImageChops, ImageDraw

IDENTITY = (1, 0, 0, 1, 0, 0)


def number(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or abs(value) > 1e6:
        raise ValueError("Invalid scene coordinate")
    return value


def multiply(l, r):
    a,b,c,d,x,y=l; A,B,C,D,X,Y=r
    return (a*A+c*B,b*A+d*B,a*C+c*D,b*C+d*D,a*X+c*Y+x,b*X+d*Y+y)


def apply(t, x, y):
    a,b,c,d,tx,ty=t
    return a*x+c*y+tx,b*x+d*y+ty


def rgba(value, opacity=1):
    channels = [number(value[k]) for k in ("r", "g", "b", "a")]
    if not all(0 <= c <= 1 for c in channels) or not 0 <= opacity <= 1:
        raise ValueError("Invalid scene color/opacity")
    return tuple(round(c*255) for c in channels[:3]) + (round(channels[3]*opacity*255),)


def render(report: dict, resource_root: Path) -> Image.Image:
    root = report["scene"]
    dimensions = root["bounds"]["size"]
    width, height = number(dimensions["width"]), number(dimensions["height"])
    if width % 1 or height % 1 or not (1 <= width <= 4096 and 1 <= height <= 4096) or width * height > 8_000_000:
        raise ValueError("Output dimensions refused")
    size = int(width), int(height)
    output = Image.new("RGBA", size)
    cache = {}
    decoded_pixels = 0
    nodes = 0
    deadline = time.monotonic() + 10

    def image(texture):
        nonlocal decoded_pixels
        name = texture["path"]
        if not isinstance(name, str) or "\\" in name or "\x00" in name or name.startswith("/") or ".." in PurePosixPath(name).parts:
            raise ValueError("Unsafe texture path")
        if name in cache:
            result = cache[name]
            if result.size != (texture["width"], texture["height"]):
                raise ValueError("Inconsistent texture geometry")
            return result
        if len(cache) >= 64:
            raise ValueError("Texture count limit")
        path = resource_root
        for part in PurePosixPath(name).parts:
            path = path / part
            if path.is_symlink():
                raise ValueError("Texture symlink refused")
        if path.suffix != ".png" or path.stat().st_size > 4 * 1024 * 1024:
            raise ValueError("Texture type/size refused")
        with Image.open(path) as source:
            w,h=source.size
            if source.format != "PNG" or source.size != (texture["width"],texture["height"]) or not (0 < w <= 8192 and 0 < h <= 8192) or w*h > 16_000_000:
                raise ValueError("Texture geometry refused")
            decoded_pixels += w*h
            if decoded_pixels > 32_000_000:
                raise ValueError("Decoded texture limit")
            result = source.convert("RGBA")
        cache[name] = result
        return result

    def composite(plane, clip):
        plane.putalpha(ImageChops.multiply(plane.getchannel("A"), clip))
        output.alpha_composite(plane)

    def visit(node, parent, inherited_opacity, inherited_clip, depth):
        nonlocal nodes
        if node.get("shape") or node.get("gradient"):
            raise ValueError("Shape/gradient scenes require the Android compositor; desktop reference supports layers/images only")
        nodes += 1
        if depth > 64 or nodes > 4096 or time.monotonic() >= deadline:
            raise ValueError("Scene traversal limit")
        if type(node["hidden"]) is not bool or type(node["maskToBounds"]) is not bool:
            raise ValueError("Invalid scene flags")
        if node["hidden"]:
            return
        opacity = number(node["opacity"])
        if not 0 <= opacity <= 1:
            raise ValueError("Invalid opacity")
        opacity *= inherited_opacity
        b,p,a=node["bounds"],node["position"],node["anchorPoint"]
        x,y=number(b["origin"]["x"]),number(b["origin"]["y"])
        w,h=number(b["size"]["width"]),number(b["size"]["height"])
        if w < 0 or h < 0:
            raise ValueError("Negative bounds")
        t=tuple(number(node["transform"][k]) for k in ("a","b","c","d","tx","ty"))
        local=multiply((1,0,0,1,number(p["x"]),number(p["y"])),t)
        local=multiply(local,(1,0,0,1,-number(a["x"])*w-x,-number(a["y"])*h-y))
        world=multiply(parent,local)
        points=[apply(world,xx,yy) for xx,yy in ((x,y),(x+w,y),(x+w,y+h),(x,y+h))]
        for point in points:
            for coordinate in point:
                number(coordinate)
        mask=Image.new("L",size)
        ImageDraw.Draw(mask).polygon(points,fill=255)
        clip=ImageChops.multiply(inherited_clip,mask) if node["maskToBounds"] else inherited_clip
        plane=Image.new("RGBA",size)
        ImageDraw.Draw(plane).polygon(points,fill=rgba(node["backgroundColor"],opacity))
        composite(plane,clip)
        texture=node.get("texture")
        if texture and w > 0 and h > 0 and opacity > 0:
            pixels=image(texture).copy()
            tint=rgba(node["tint"],opacity)
            pixels=ImageChops.multiply(pixels,Image.new("RGBA",pixels.size,tint))
            mapping=multiply(world,(w/pixels.width,0,0,h/pixels.height,x,y))
            A,B,C,D,X,Y=mapping
            determinant=A*D-B*C
            if abs(determinant)>1e-12:
                inverse=(D/determinant,-C/determinant,(C*Y-D*X)/determinant,
                         -B/determinant,A/determinant,(B*X-A*Y)/determinant)
                warped=pixels.transform(size,Image.Transform.AFFINE,inverse,Image.Resampling.BICUBIC)
                composite(warped,clip)
        children=node["children"]
        if not isinstance(children,list) or len(children)>4096:
            raise ValueError("Invalid child list")
        for child in children:
            visit(child,world,opacity,clip,depth+1)

    visit(root,IDENTITY,1,Image.new("L",size,255),0)
    return output


if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scene",type=Path)
    parser.add_argument("--resources",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    try:
        if args.scene.stat().st_size>1024*1024:
            raise ValueError("Scene input limit")
        result=render(json.loads(args.scene.read_text()),args.resources.resolve())
        result.save(args.output)
        print(f"Rendered offscreen fixture {result.width}x{result.height}; no Ultra session claimed.")
    except (ValueError,KeyError,TypeError,OSError) as error:
        parser.exit(1,f"Rendering failed: {error}\n")
