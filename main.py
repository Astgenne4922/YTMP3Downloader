import tempfile as tf
import tkinter as tk
import gui

if __name__ == "__main__":
    with tf.TemporaryDirectory() as temp_dir:
        root = tk.Tk()
        root.resizable(0, 0)
        root.geometry("600x375")

        gui.Window(root, temp_dir).pack(side="top", fill="both", expand=True)

        root.mainloop()
