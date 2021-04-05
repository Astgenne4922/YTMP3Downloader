"""
#region Imports
"""

import matplotlib.backends.backend_tkagg as tkagg
import tkinter.simpledialog as dlg
import matplotlib.figure as fg
import scipy.io.wavfile as wf
import tkinter.ttk as ttk
import numpy as np

"""
#endregion
"""


class Draggable_Min_Max:
    def __init__(self, fig, ax, canvas, min, max):
        self.fig = fig
        self.ax = ax
        self.c = canvas
        self.min = min
        self.max = max

        self.lineMin = self.ax.axvline(min, picker=15, linewidth=3)
        self.lineMin.set_xdata(min)
        self.lineMax = self.ax.axvline(max, picker=15, linewidth=3)
        self.lineMax.set_xdata(max)

        self.c.mpl_connect("button_press_event", self.on_click)
        self.draw_cid = self.c.mpl_connect("draw_event", self.grab_background)

    def on_click(self, event):
        containsMin, _ = self.lineMin.contains(event)
        containsMax, _ = self.lineMax.contains(event)
        if (containsMin or containsMax) and event.button == 1:
            self.drag_cids = [
                self.c.mpl_connect("motion_notify_event", self.drag_update),
                self.c.mpl_connect("button_release_event", self.end_drag),
            ]

    def drag_update(self, event):
        if event.xdata is not None:
            if self.lineMin.contains(event)[0]:
                self.lineMin.set_xdata(
                    self.min
                    if self.min > event.xdata
                    else self.max
                    if self.max < event.xdata
                    else event.xdata
                )
            elif self.lineMax.contains(event)[0]:
                self.lineMax.set_xdata(
                    self.min
                    if self.min > event.xdata
                    else self.max
                    if self.max < event.xdata
                    else event.xdata
                )
            self.blit()

    def end_drag(self, _):
        for cid in self.drag_cids:
            self.c.mpl_disconnect(cid)

    def grab_background(self, _):
        self.lineMin.set_visible(False)
        self.lineMax.set_visible(False)

        self.c.mpl_disconnect(self.draw_cid)
        self.c.draw()
        self.draw_cid = self.c.mpl_connect("draw_event", self.grab_background)

        self.background = self.c.copy_from_bbox(self.fig.bbox)

        self.lineMin.set_visible(True)
        self.lineMax.set_visible(True)
        self.blit()

    def blit(self):
        self.c.restore_region(self.background)
        self.ax.draw_artist(self.lineMin)
        self.ax.draw_artist(self.lineMax)
        self.c.blit(self.fig.bbox)

    def getMinMax(self):
        return sorted((self.lineMin.get_xdata(), self.lineMax.get_xdata()))


class CutDialog(dlg.Dialog):
    def buttonbox(self) -> None:
        self.resizable(0, 0)
        self.focus()

        box = ttk.Frame(self)
        box.pack()

        plotFrame = ttk.Frame(box)
        plotFrame.pack()

        btnFrame = ttk.Frame(box)
        btnFrame.pack()

        samplerate, data = wf.read(self.master.WAV)
        duration = len(data) / samplerate
        time = np.linspace(0, duration, len(data))

        fig = fg.Figure(figsize=(10, 4), dpi=100)
        ax = fig.add_subplot(111)
        canvas = tkagg.FigureCanvasTkAgg(fig, master=plotFrame)

        ax.plot(time, data)
        self.min_max = Draggable_Min_Max(fig, ax, canvas, 0, int(time[-1]))
        self.result = (0, int(time[-1]))

        canvas.draw()
        canvas.get_tk_widget().pack(side="top", fill="both", expand=1)

        ttk.Button(master=btnFrame, text="Ok", command=self.__choice).pack()

    def __choice(self) -> None:
        self.ok()
        self.result = self.min_max.getMinMax()
