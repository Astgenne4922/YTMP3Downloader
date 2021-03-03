import pytube, subprocess, pathlib

temps = [
    pathlib.Path(f"temp/{x}")
    for x in ["phase0.mp4", "phase1.mp3", "phase2.mp3", "phase3.mp3", ""]
]

v = pytube.YouTube("https://youtu.be/vcGbefQBvJ4")
t = v.thumbnail_url
v.streams.get_highest_resolution().download(output_path="temp", filename="phase0")

subprocess.call(f"ffmpeg -i temp/phase0.mp4 temp/phase1.mp3", shell=True)
subprocess.call(
    f"ffmpeg -i temp/phase1.mp3 -i {t} -map 0:0 -map 1:0 -c copy -id3v2_version 3 temp/phase2.mp3",
    shell=True,
)
subprocess.call(
    f"ffmpeg -ss 0 -t 40 -i temp/phase2.mp3 -c copy -id3v2_version 3 temp/phase3.mp3",
    shell=True,
)
subprocess.call(
    f'ffmpeg -i temp/phase3.mp3 -c copy -id3v2_version 3 -metadata title="Anchor" -metadata artist="Yoasobi" -metadata album="sas" "Yoasobi - Anchor.mp3"'
)

for f in temps:
    if f.is_file():
        f.unlink()
    else:
        f.rmdir()
