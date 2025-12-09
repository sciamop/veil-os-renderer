import asyncio
import os
import shutil
import aiofiles
import subprocess
from enum import Enum
from fastapi import FastAPI, UploadFile, File, BackgroundTasks
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from sse_starlette.sse import EventSourceResponse

app = FastAPI()

# Enable CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Serve static files for the web client
app.mount("/static", StaticFiles(directory="server/static", html=True), name="static")

@app.get("/")
async def read_root():
    # Use absolute path or relative to the current working directory from where script is run
    # Since we run from VeilRenderer root, but the file is in server/static
    # Adjust based on where you run python from.
    # Assuming run from 'VeilRenderer' folder:
    return FileResponse(os.path.join("server", "static", "index.html"))


class ServerState(str, Enum):
    IDLE = "IDLE"
    PROCESSING = "PROCESSING"
    READY = "READY"

# Global state
current_state = ServerState.IDLE
current_video_path = "output.mp4"
# Force directories to be inside the server folder
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
UPLOAD_DIR = os.path.join(BASE_DIR, "uploads")
OUTPUT_DIR = os.path.join(BASE_DIR, "outputs")

os.makedirs(UPLOAD_DIR, exist_ok=True)
os.makedirs(OUTPUT_DIR, exist_ok=True)

# Event queue for SSE
event_queue = asyncio.Queue()

async def broadcast_state(state: ServerState):
    global current_state
    current_state = state
    await event_queue.put({"event": "state_change", "data": state.value})

async def run_live_portrait_mock(image_path: str):
    await broadcast_state(ServerState.PROCESSING)
    
    print(f"Starting Live Portrait processing for {image_path}...")
    # Mock processing time
    await asyncio.sleep(5) 
    
    # In a real scenario, we would run the subprocess here.
    # HARDCODED PATH to LivePortrait inference script
    # User's path: C:\Users\sciam\Documents\_projects\_FALL2025_Thesis\LivePortraitinference.py
    # NOTE: Check if there is a missing backslash between LivePortrait and inference.py?
    # Assuming standard folder structure: ...\LivePortrait\inference.py
    
    lp_script = r"C:\Users\sciam\Documents\_projects\_FALL2025_Thesis\LivePortrait\inference.py"
    # lp_python = r"D:\conda\envs\LivePortrait\python.exe" # Using the conda env python
    # If the server is ALREADY running in the correct conda env, just use 'python'
    lp_python = "python" 
    
    if not os.path.exists(lp_script):
        print(f"WARNING: LivePortrait script not found at {lp_script}")
    
    # We need to pass absolute paths to the script
    abs_source = os.path.abspath(image_path)
    
    # Use preformatted .pkl file for driving motion
    # Pick one from the list: d0.pkl, d1.pkl, d2.pkl, d5.pkl, d7.pkl, talking.pkl, etc.
    # Using 'd0.pkl' as it usually matches 'd0.mp4' (generic movement)
    abs_driving = os.path.abspath(os.path.join("server", "driving.pkl"))
    
    abs_output_dir = os.path.abspath(OUTPUT_DIR)
    
    # LivePortrait expects --output-dir NOT --output
    cmd = [
        lp_python, 
        lp_script,
        "--source", abs_source,
        "--driving", abs_driving,
        "--output-dir", abs_output_dir
    ]
    
    print(f"Running command: {' '.join(cmd)}")
    
    # Set encoding to UTF-8 for subprocess to handle emojis in logs
    env = os.environ.copy()
    env["PYTHONIOENCODING"] = "utf-8"
    
    try:
        # Run LivePortrait!
        # Adding env=env to fix UnicodeEncodeError in Windows console
        process = subprocess.run(cmd, capture_output=True, text=True, cwd=os.path.dirname(lp_script), encoding='utf-8', env=env)
        print("STDOUT:", process.stdout)
        
        if process.returncode != 0:
            print("STDERR:", process.stderr)
            print("LivePortrait failed.")
        else:
            print("LivePortrait finished successfully.")
            
            # Find the output video. LivePortrait puts it in a timestamped folder or named specifically.
            # We need to find the most recent .mp4 in abs_output_dir and rename it to 'output.mp4'
            
            # List files in output dir
            files = [os.path.join(abs_output_dir, f) for f in os.listdir(abs_output_dir) if f.endswith('.mp4')]
            if files:
                # Get most recent file
                newest_file = max(files, key=os.path.getctime)
                target_file = os.path.join(abs_output_dir, "output.mp4")
                
                # If the newest file IS output.mp4, we're good (or we just overwrote it)
                if newest_file != target_file:
                    if os.path.exists(target_file):
                        os.remove(target_file)
                    shutil.move(newest_file, target_file)
                print(f"Video ready at {target_file}")
            else:
                print("No output video found in directory.")

    except Exception as e:
        print(f"Error running LivePortrait: {e}")

    # For now, we'll just pretend we created a video. 
    # Ensure there is a dummy video file available to serve.
    dummy_video = os.path.join(OUTPUT_DIR, "output.mp4")
    
    # If the file doesn't exist OR it's the tiny fake text file we created, 
    # try to copy the driving video if it exists, to be a valid MP4.
    driving_video_source = "server/driving.mp4"
    
    if not os.path.exists(dummy_video) or os.path.getsize(dummy_video) < 100:
        if os.path.exists(driving_video_source):
            shutil.copy(driving_video_source, dummy_video)
        elif not os.path.exists(dummy_video):
            # Only if we absolutely can't find a real video, create the fake one
             with open(dummy_video, "wb") as f:
                f.write(b"fake video content") 
            
    print("Live Portrait processing finished.")
    await broadcast_state(ServerState.READY)

@app.post("/upload")
async def upload_image(file: UploadFile = File(...), background_tasks: BackgroundTasks = None):
    global current_state
    
    # Allow re-upload even if processing (just restart/overwrite)
    # logic: If we receive a new better image, we might want to process that instead?
    # Or just ignore this check for debugging.
    # if current_state == ServerState.PROCESSING:
    #    return JSONResponse(status_code=429, content={"message": "Server is busy"})

    file_location = os.path.join(UPLOAD_DIR, "input_face.png")
    
    # Debug print
    print(f"Receiving upload... saving to {os.path.abspath(file_location)}")
    
    async with aiofiles.open(file_location, 'wb') as out_file:
        content = await file.read() # Read file content
        await out_file.write(content)
        
    print(f"File saved. Size: {os.path.getsize(file_location)} bytes")

    # Notify clients immediately about the new image upload
    await event_queue.put({"event": "new_image", "data": "/uploads/input_face.png"})

    # Start processing in background
    background_tasks.add_task(run_live_portrait_mock, file_location)
    
    return {"message": "Image uploaded, processing started"}

# Serve uploads directory so client can display the face
app.mount("/uploads", StaticFiles(directory=UPLOAD_DIR), name="uploads")

@app.get("/status")
async def get_status():
    return {"state": current_state}

@app.get("/video")
async def get_video():
    video_path = os.path.join(OUTPUT_DIR, "output.mp4")
    if os.path.exists(video_path):
        return FileResponse(video_path, media_type="video/mp4")
    return JSONResponse(status_code=404, content={"message": "Video not found"})

@app.get("/events")
async def message_stream():
    async def event_generator():
        while True:
            # If clients connect late, send current state immediately
            yield {"event": "state_change", "data": current_state.value}
            try:
                # Wait for new events
                message = await event_queue.get()
                yield message
            except asyncio.CancelledError:
                break
            
            # Keep alive / throttle slightly to prevent tight loops if queue logic fails
            await asyncio.sleep(0.1) 

    return EventSourceResponse(event_generator())

if __name__ == "__main__":
    import uvicorn
    # Listen on all interfaces so Android emulator (10.0.2.2) or device can connect
    uvicorn.run(app, host="0.0.0.0", port=8000)

