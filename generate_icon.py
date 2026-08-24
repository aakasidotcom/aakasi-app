import zlib
import struct
import math

def create_png(width, height, draw_func):
    # RGBA image buffer
    pixels = bytearray(width * height * 4)
    for y in range(height):
        for x in range(width):
            r, g, b, a = draw_func(x, y, width, height)
            idx = (y * width + x) * 4
            pixels[idx] = r
            pixels[idx+1] = g
            pixels[idx+2] = b
            pixels[idx+3] = a

    # PNG generation
    raw_data = bytearray()
    for y in range(height):
        raw_data.append(0) # Filter type 0
        raw_data.extend(pixels[y*width*4 : (y+1)*width*4])

    compressed = zlib.compress(raw_data)
    
    def chunk(tag, data):
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)

    ihdr = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr) + chunk(b'IDAT', compressed) + chunk(b'IEND', b'')
    return png

def dist_to_segment(px, py, x1, y1, x2, y2):
    dx = x2 - x1
    dy = y2 - y1
    if dx == 0 and dy == 0:
        return math.hypot(px - x1, py - y1)
    t = ((px - x1) * dx + (py - y1) * dy) / (dx*dx + dy*dy)
    t = max(0.0, min(1.0, t))
    closest_x = x1 + t * dx
    closest_y = y1 + t * dy
    return math.hypot(px - closest_x, py - closest_y)

def dist_to_circle_arc(px, py, cx, cy, r):
    return abs(math.hypot(px - cx, py - cy) - r)

def draw_cloud_off(x, y, w, h):
    # Scale to 100x100
    sx = x * 100.0 / w
    sy = y * 100.0 / h

    stroke_width = 4.5

    min_dist = 999.0

    # 1. Diagonal line from (15, 15) to (85, 85)
    d_line = dist_to_segment(sx, sy, 15, 15, 85, 85)
    min_dist = min(min_dist, d_line)

    # 2. Bottom line from (28, 75) to (75, 75)
    d_bot = dist_to_segment(sx, sy, 28, 75, 75, 75)
    min_dist = min(min_dist, d_bot)

    # 3. Bottom-left arc centered at (28, 62) r=13
    d_bl = dist_to_circle_arc(sx, sy, 28, 62, 13)
    if sx <= 28 and sy >= 49 and sy <= 75:
        min_dist = min(min_dist, d_bl)

    # 4. Top-left arc centered at (38, 48) r=15
    d_tl = dist_to_circle_arc(sx, sy, 38, 48, 15)
    if sx >= 23 and sx <= 53 and sy <= 48:
        min_dist = min(min_dist, d_tl)

    # 5. Top-right main arc centered at (58, 42) r=20
    d_tr = dist_to_circle_arc(sx, sy, 58, 42, 20)
    if sx >= 45 and sx <= 78 and sy <= 42:
        min_dist = min(min_dist, d_tr)

    # 6. Right arc centered at (75, 60) r=15
    d_r = dist_to_circle_arc(sx, sy, 75, 60, 15)
    if sx >= 75 and sy >= 45 and sy <= 75:
        min_dist = min(min_dist, d_r)

    # Antialiasing
    half = stroke_width / 2.0
    if min_dist <= half - 0.75:
        alpha = 255
    elif min_dist <= half + 0.75:
        alpha = int(255 * (1.0 - (min_dist - (half - 0.75)) / 1.5))
    else:
        alpha = 0

    return (0, 0, 0, alpha)

png_bytes = create_png(128, 128, draw_cloud_off)
with open("app/src/main/res/drawable/ic_cloud_off_attached.png", "wb") as f:
    f.write(png_bytes)

print("Generated PNG successfully, size:", len(png_bytes))
