import sys
from PIL import Image

img = Image.open(sys.argv[1])
bbox = img.getbbox()
print("Size:", img.size)
print("BBox:", bbox)
