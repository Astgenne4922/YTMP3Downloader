from tkinter.constants import BOTH, CENTER, DISABLED, NORMAL, LEFT, RIGHT, TOP, X
from tkinter.ttk import Entry, Button, Frame, Label
from subprocess import Popen, CREATE_NO_WINDOW
from string import ascii_uppercase, digits
from tkinter import StringVar, Tk
from waveplot import CutDialog
from PIL import ImageTk, Image
from threading import Thread
from pytube import YouTube
from random import choices
from requests import get
from pathlib import Path
import os


"""
https://www.youtube.com/watch?v=vcGbefQBvJ4
"""
FFMPEG = "FFmpeg/bin/ffmpeg"
VIDEO = "temp/vid.mp4"
AUDIO = "temp/aud"
THUMB = "temp/thumb.jpg"


class Window(Frame):
    def __init__(self, parent, *args, **kwargs) -> None:
        super().__init__(parent, *args, **kwargs)
        self.minCut = 0
        self.maxCut = None

        urlFrame = Frame(self)
        urlFrame.pack(fill=X)

        Label(urlFrame, text="Video URL:").pack(side=LEFT, padx=5, pady=5)
        Button(urlFrame, text="Enter", command=self.spawnThread).pack(
            side=RIGHT, padx=5, pady=5
        )
        self.url = StringVar(self)
        Entry(urlFrame, textvariable=self.url).pack(fill=X, padx=5, pady=5, expand=True)

        imgFrame = Frame(self)
        imgFrame.pack(fill=X)

        self.panel = Label(imgFrame)
        self.panel.pack(anchor=CENTER)
        self.changeImg("placeholder.jpg")

        metaFrame = Frame(self)
        metaFrame.pack(anchor=CENTER)

        Label(metaFrame, text="Author:").pack(side=LEFT, padx=5, pady=5)
        self.author = StringVar(self, value="")
        self.authorEntry = Entry(metaFrame, textvariable=self.author, state=DISABLED)
        self.authorEntry.pack(side=LEFT, padx=5, pady=5)

        Label(metaFrame, text="Title:").pack(side=LEFT, padx=5, pady=5)
        self.title = StringVar(self, value="")
        self.titleEntry = Entry(metaFrame, textvariable=self.title, state=DISABLED)
        self.titleEntry.pack(side=LEFT, padx=5, pady=5)

        self.cutBtn = Button(metaFrame, text="Cut", command=self.cut, state=DISABLED)
        self.cutBtn.pack(side=RIGHT, padx=5, pady=5)
        self.convertBtn = Button(
            metaFrame, text="Convert And Download", command=self.convert, state=DISABLED
        )
        self.convertBtn.pack(side=RIGHT, padx=5, pady=5)

    def updateVideo(self, author, title):
        self.changeImg(THUMB)
        self.author.set(author)
        self.title.set(title)

    def getVideo(self):
        video = YouTube(self.url.get())

        self.maxCut = video.length

        with open(THUMB, "wb") as f:
            f.write(get(video.thumbnail_url).content)

        video.streams.get_highest_resolution().download(
            output_path="temp", filename="vid"
        )

        self.updateVideo(
            "".join(e for e in video.author if e.isascii() and e.isalpha()),
            "".join(e for e in video.title if e.isascii() and e.isalpha()),
        )

        Popen(
            f"{FFMPEG} -i {VIDEO} {AUDIO}.wav",
            creationflags=CREATE_NO_WINDOW if os.name == "nt" else None,
        )
        Popen(
            f"{FFMPEG} -i {VIDEO} {AUDIO}.mp3",
            creationflags=CREATE_NO_WINDOW if os.name == "nt" else None,
        )

        self.authorEntry["state"] = NORMAL
        self.titleEntry["state"] = NORMAL
        self.cutBtn["state"] = NORMAL
        self.convertBtn["state"] = NORMAL

    def spawnThread(self):
        t = Thread(target=self.getVideo, daemon=True)
        t.start()

    def cut(self):
        self.minCut, self.maxCut = CutDialog(self).result

    def convert(self):
        album = "".join(choices(ascii_uppercase + digits, k=10))
        Popen(
            f'{FFMPEG} -ss {self.minCut} -t {self.maxCut} -i {AUDIO}.mp3 -i {THUMB} -map 0:0 -map 1:0 -c copy -id3v2_version 3 -metadata title="{self.title.get()}" -metadata artist="{self.author.get()}" -metadata album="{album}" "{self.author.get()} - {self.title.get()}.mp3"',
            creationflags=CREATE_NO_WINDOW if os.name == "nt" else None,
        )

        self.changeImg("placeholder.jpg")

        self.author.set("")
        self.title.set("")

    def changeImg(self, path):
        baseheigth = 300
        img = Image.open(path)
        hpercent = baseheigth / float(img.size[1])
        wsize = int((float(img.size[0]) * float(hpercent)))
        img = img.resize((wsize, baseheigth), Image.ANTIALIAS)
        self.img = ImageTk.PhotoImage(img)
        self.panel.config(image=self.img)


def clearTemp():
    t = Path("temp")
    for i in t.iterdir():
        i.unlink()


def __onwindowclose():
    clearTemp()
    root.destroy()


root = Tk()
root.resizable(0, 0)
root.geometry("600x375")
root.protocol("WM_DELETE_WINDOW", __onwindowclose)

Window(root).pack(side=TOP, fill=BOTH, expand=True)

root.mainloop()
