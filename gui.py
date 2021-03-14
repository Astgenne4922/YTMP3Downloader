"""
#region Imports
"""

import tkinter.filedialog as dlg
import tkinter.messagebox as mb
import PIL.ImageTk as pilTk
import tkinter.ttk as ttk
import subprocess as sp
import threading as thr
import waveplot as wp
import requests as rq
import tkinter as tk
import pathlib as pl
import pytube as pyt
import random as rnd
import string as st
import PIL as pil
import os
import re

"""
#endregion
"""

FFMPEG = r"FFmpeg\ffmpeg"
NOVID = r"res\placeholder.jpg"
YTREGEX = r"^(https://www\.youtube\.com/watch\?v=|https://www\.youtube\.com/embed/|https://youtu\.be/)[0-9A-Za-z_-]{11}$"
NO_CONSOLE = sp.CREATE_NO_WINDOW if os.name == "nt" else None


class Window(ttk.Frame):
    def __init__(self, parent, tempdir, *args, **kwargs) -> None:
        super().__init__(parent, *args, **kwargs)

        self.startCut = 0
        self.cutLen = None

        """
        #region Files and directories
        """

        self.VIDEO = f"{tempdir}/vid.mp4"
        self.THUMB = f"{tempdir}/thumb.jpg"
        self.MP3 = f"{tempdir}/aud.mp3"
        self.WAV = f"{tempdir}/aud.wav"
        self.tempdir = tempdir

        """
        #endregion
        """

        """
        #region Frames creation
        """

        urlFrame = ttk.Frame(self)
        urlFrame.pack(fill="x")

        imgFrame = ttk.Frame(self)
        imgFrame.pack(fill="both", expand=True, anchor="center")

        metaFrame = ttk.Frame(self)
        metaFrame.pack(anchor="s")

        """
        #endregion
        """

        """
        #region URL Input widgets setting
        """

        ttk.Label(urlFrame, text="Video URL:").pack(
            side="left",
            padx=5,
            pady=5,
        )
        ttk.Button(urlFrame, text="Enter", command=self.threadYT).pack(
            side="right",
            padx=5,
            pady=5,
        )
        self.url = tk.StringVar(self)
        ttk.Entry(urlFrame, textvariable=self.url).pack(
            fill="x",
            padx=5,
            pady=5,
            expand=True,
        )

        """
        #endregion
        """

        """
        #region Thumbnail/ProgressBar widgets setting
        """

        self.panel = ttk.Label(imgFrame)
        self.panel.pack(anchor="center")
        self.changeImg(NOVID)

        self.progress = ttk.Progressbar(
            imgFrame,
            orient="horizontal",
            length=100,
            mode="indeterminate",
        )
        self.progress.place(relx=0.5, rely=0.5, anchor="center")
        self.progress.place_forget()

        """
        #endregion
        """

        """
        #region Metadata/Convert/Cut widgets setting
        """

        ttk.Label(metaFrame, text="Author:").pack(
            side="left",
            padx=5,
            pady=5,
        )
        self.author = tk.StringVar(self, value="")
        self.authorEntry = ttk.Entry(
            metaFrame,
            textvariable=self.author,
            state="disabled",
        )
        self.authorEntry.pack(side="left", padx=5, pady=5)

        ttk.Label(metaFrame, text="Title:").pack(
            side="left",
            padx=5,
            pady=5,
        )
        self.title = tk.StringVar(self, value="")
        self.titleEntry = ttk.Entry(
            metaFrame,
            textvariable=self.title,
            state="disabled",
        )
        self.titleEntry.pack(side="left", padx=5, pady=5)

        self.cutBtn = ttk.Button(
            metaFrame,
            text="Cut",
            command=self.cut,
            state="disabled",
        )
        self.cutBtn.pack(side="right", padx=5, pady=5)
        self.convertBtn = ttk.Button(
            metaFrame,
            text="Convert And Download",
            command=self.convert,
            state="disabled",
        )
        self.convertBtn.pack(side="right", padx=5, pady=5)

        """
        #endregion
        """

    def getVideo(self):
        """
        #region Validate input
        """

        if not re.match(YTREGEX, self.url.get().strip()):
            mb.showerror("Error", "Invalid URL")
            return

        """
        #endregion
        """

        try:
            """
            #region Recover video from URL
            """

            video = pyt.YouTube(self.url.get().strip())

            self.cutLen = video.length

            with open(self.THUMB, "wb") as f:
                f.write(rq.get(video.thumbnail_url).content)

            video.streams.get_highest_resolution().download(
                output_path=self.tempdir,
                filename="vid",
            )

            """
            #endregion
            """

            """
            #region Video to audio convertion
            """

            sp.run(
                f"{FFMPEG} -i {self.VIDEO} {self.WAV} {self.MP3}",
                creationflags=NO_CONSOLE,
            )

            """
            #endregion 
            """

            """
            #region GUI update
            """

            self.progress.stop()
            self.progress.place_forget()
            self.panel.pack(anchor="center")

            self.updateVideo(
                self.THUMB,
                normalizeText(video.author),
                normalizeText(video.title),
            )

            self.enable_disable_meta(True)

            """
            #endregion
            """
        except Exception as e:
            mb.showerror("Error", e)

    def threadYT(self):
        t = thr.Thread(target=self.getVideo, daemon=True)
        t.start()
        self.panel.pack_forget()
        self.progress.place(relx=0.5, rely=0.5, anchor="center")
        self.progress.start(10)

    def cut(self):
        res = wp.CutDialog(self).result
        self.startCut = res[0]
        self.cutLen = res[1] - self.startCut

    def convert(self):
        album = "".join(rnd.choices(st.ascii_letters + st.digits, k=10))

        """
        #region Validating input
        """

        title = self.title.get().strip()
        author = self.author.get().strip()

        if title != normalizeText(title):
            mb.showerror("Error", "Invalid title")
            return

        if author != normalizeText(author):
            mb.showerror("Error", "Invalid author")
            return

        final_folder = dlg.askdirectory().replace("/", "\\")
        if final_folder == "":
            return

        if os.path.isfile(f"{final_folder}/{author} - {title}.mp3"):
            mb.showerror(
                "Error",
                "The selected directory already contains this song",
            )
            return

        """
        #endregion
        """

        t = thr.Thread(
            target=self.threadConv,
            args=(
                self.startCut,
                self.cutLen,
                title,
                author,
                album,
                final_folder,
            ),
            daemon=True,
        )
        t.start()

        self.panel.pack_forget()
        self.progress.place(relx=0.5, rely=0.5, anchor="center")
        self.progress.start(10)

    def threadConv(self, startCut, cutLen, title, author, album, destination_folder):
        """
        #region Add thumbnail, metadata and save in user selected folder
        """

        cut = f"-ss {startCut} -t {cutLen}"
        opt = "-map 0:0 -map 1:0 -c copy -id3v2_version 3"
        metadata = f'-metadata title="{title}" -metadata artist="{author}" -metadata album="{album}"'
        out = fr'"{destination_folder}\{author} - {title}.mp3"'
        sp.run(
            f"{FFMPEG} {cut} -i {self.MP3} -i {self.THUMB} {opt} {metadata} {out}",
            creationflags=NO_CONSOLE,
        )

        """
        #endregion
        """

        """
        #region GUI update
        """

        self.progress.stop()
        self.progress.place_forget()
        self.panel.pack(anchor="center")

        self.url.set("")
        self.updateVideo(NOVID, "", "")
        self.enable_disable_meta(False)

        """
        #endregion
        """

        pl.Path(self.MP3).unlink()
        pl.Path(self.WAV).unlink()
        pl.Path(self.THUMB).unlink()

        sp.run(fr"explorer /select,{out}")

    def changeImg(self, path):
        baseheigth = 300
        img = pil.Image.open(path)
        hpercent = baseheigth / float(img.size[1])
        wsize = int((float(img.size[0]) * float(hpercent)))
        img = img.resize((wsize, baseheigth), pil.Image.ANTIALIAS)
        self.img = pilTk.PhotoImage(img)
        self.panel.config(image=self.img)

    def updateVideo(self, thumb, author, title):
        self.changeImg(thumb)
        self.author.set(author)
        self.title.set(title)

    def enable_disable_meta(self, enable):
        self.authorEntry["state"] = "normal" if enable else "disabled"
        self.titleEntry["state"] = "normal" if enable else "disabled"
        self.cutBtn["state"] = "normal" if enable else "disabled"
        self.convertBtn["state"] = "normal" if enable else "disabled"


def normalizeText(text):
    return "".join(e for e in text if e.isascii() and e.isalpha())
