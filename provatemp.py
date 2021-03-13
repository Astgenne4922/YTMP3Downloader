import tempfile

temp_dir = tempfile.TemporaryDirectory()
print(temp_dir.name)
# use temp_dir, and when done:
input("press to continue...")
temp_dir.cleanup()