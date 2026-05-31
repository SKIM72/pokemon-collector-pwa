let stream = null;

export async function startCamera(video) {
  if (!navigator.mediaDevices?.getUserMedia) {
    throw new Error("카메라를 사용할 수 없습니다.");
  }
  if (!video) {
    throw new Error("카메라 화면을 준비 중입니다.");
  }

  stream = await navigator.mediaDevices.getUserMedia({
    audio: false,
    video: {
      facingMode: { ideal: "environment" },
      width: { ideal: 1280 },
      height: { ideal: 1920 },
    },
  });

  video.srcObject = stream;
  await video.play();
  return stream;
}

export function stopCamera(video) {
  stream?.getTracks().forEach((track) => track.stop());
  stream = null;
  if (video) video.srcObject = null;
}

export function captureFrame(video, canvas) {
  const width = video.videoWidth || 900;
  const height = video.videoHeight || 1200;
  canvas.width = width;
  canvas.height = height;

  const context = canvas.getContext("2d");
  context.drawImage(video, 0, 0, width, height);

  return canvas.toDataURL("image/jpeg", 0.88);
}
