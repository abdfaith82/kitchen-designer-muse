// CameraOrbitController.cs
// Orbit camera for the kitchen scene: drag to orbit, scroll / pinch to
// zoom. Works with mouse in the Editor and single/two-finger touch on
// device. Pitch and distance are clamped so the camera stays above the
// floor and at a usable range.
//
// Attach to the scene camera (or any rig object); the controller drives
// its own transform to look at `target` every frame in LateUpdate.

using UnityEngine;

namespace KitchenDesigner
{
    public class CameraOrbitController : MonoBehaviour
    {
        [Header("Orbit target (world position, metres)")]
        [SerializeField] private Vector3 target = new Vector3(2f, 0f, 2f);

        [Header("Limits")]
        [SerializeField] private float minDistance = 1.5f;
        [SerializeField] private float maxDistance = 20f;
        [SerializeField] private float minPitchDeg = 5f;
        [SerializeField] private float maxPitchDeg = 85f;

        [Header("Speeds")]
        [SerializeField] private float rotateSpeedDegPerPixel = 0.25f;
        [SerializeField] private float zoomSpeed = 0.1f;

        private float yawDeg = -45f;
        private float pitchDeg = 45f;
        private float distance = 7f;

        /// <summary>Move the orbit target (e.g. to a newly loaded room's centre) and optionally reset the distance.</summary>
        public void SetTarget(Vector3 newTarget, float newDistance = -1f)
        {
            target = newTarget;
            if (newDistance > 0f) distance = Mathf.Clamp(newDistance, minDistance, maxDistance);
        }

        private void LateUpdate()
        {
            HandleInput();

            pitchDeg = Mathf.Clamp(pitchDeg, minPitchDeg, maxPitchDeg);
            distance = Mathf.Clamp(distance, minDistance, maxDistance);

            Quaternion rotation = Quaternion.Euler(pitchDeg, yawDeg, 0f);
            Vector3 offset = rotation * new Vector3(0f, 0f, -distance);
            transform.position = target + offset;
            transform.LookAt(target);
        }

        private void HandleInput()
        {
            if (Input.touchCount == 2)
            {
                // Pinch zoom.
                Touch t0 = Input.GetTouch(0);
                Touch t1 = Input.GetTouch(1);
                Vector2 prev0 = t0.position - t0.deltaPosition;
                Vector2 prev1 = t1.position - t1.deltaPosition;
                float prevDistance = Vector2.Distance(prev0, prev1);
                float currentDistance = Vector2.Distance(t0.position, t1.position);
                distance -= (currentDistance - prevDistance) * zoomSpeed * 0.1f;
            }
            else if (Input.touchCount == 1)
            {
                Touch t = Input.GetTouch(0);
                if (t.phase == TouchPhase.Moved)
                {
                    yawDeg -= t.deltaPosition.x * rotateSpeedDegPerPixel;
                    pitchDeg += t.deltaPosition.y * rotateSpeedDegPerPixel;
                }
            }
            else
            {
                // Mouse: left-drag orbits, wheel zooms. (A quick click without
                // drag is left for KitchenBridge's tap-to-select.)
                if (Input.GetMouseButton(0))
                {
                    yawDeg -= Input.GetAxis("Mouse X") * 4f;
                    pitchDeg += Input.GetAxis("Mouse Y") * 4f;
                }
                float scroll = Input.GetAxis("Mouse ScrollWheel");
                if (Mathf.Abs(scroll) > 0.0001f)
                    distance -= scroll * (1f / Mathf.Max(zoomSpeed, 0.01f)) * 0.1f;
            }
        }
    }
}
