//javac -classpath ".;C:\Program Files\lwjgl-release-3.3.4-custom\*" BulletHell.java
//java -classpath ".;C:\Program Files\lwjgl-release-3.3.4-custom\*" BulletHell

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;

/* SHAUN start
 * This is the main class that controls the entire program.
 * It is responsible for setting up the window, initializing OpenGL,
 * loading all major systems like terrain, bullets, and sound,
 * and then running the main game loop.
 */
public class BulletHell {
    private long window;
    private int width = 1920;
    private int height = 1080;
    private Terrain terrain;
    private BulletSystem bulletSystem;
    private SoundPlayer soundPlayer;

    /*
     * This is the entry point of the program.
     * It simply creates an instance of the game and starts it.
     */
    public static void main(String[] args) {
        //JC entry point, creates the application instance and starts the run loop
        new BulletHell().run();
    }

    /*
     * This method controls the overall lifecycle of the program.
     * It first initializes everything, then runs the main loop,
     * and finally cleans up resources when the program ends.
     */
    public void run() {
        //JC calls init to set up the window and OpenGL state, then enters the main loop
        init();
        loop();
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    /*
     * In this method, we set up everything needed before the game starts running.
     * This includes creating the window, enabling OpenGL features like depth testing
     * and lighting, and configuring the camera projection.
     *
     * We also load all major components here, including the terrain mesh,
     * the bullet system, and the sound system.
     */
    private void init() {
        //JC initializes GLFW and creates the application window
        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        //ST sets multisampling hint for anti-aliased edges then creates the window
        GLFW.glfwWindowHint(GLFW.GLFW_SAMPLES, 4);
        window = GLFW.glfwCreateWindow(width, height, "500 Bullets = WOW", 0, 0);
        if (window == 0) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        //ST makes the OpenGL context active and enables multisampling for smoother rendering
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        GL11.glEnable(GL13.GL_MULTISAMPLE);

        //JC enables depth testing so closer geometry correctly draws over farther geometry
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);

        //ST sets the background clear color to a dark reddish tone
        GL11.glClearColor(0.40f, 0.15f, 0.15f, 1.0f);

        //JC sets up the perspective projection matrix with a 60 degree field of view
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        setPerspectiveProjection(60.0f, (float) width / height, 0.1f, 2000.0f);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);

        //ST enables 2D texture mapping so surfaces can display image-based textures
        GL11.glEnable(GL11.GL_TEXTURE_2D);

        //ST enables the OpenGL lighting system and activates the first light source (LIGHT0)
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_LIGHT0);

        //ST positions the light source far above and to the front-right of the scene
        FloatBuffer lightPos = BufferUtils.createFloatBuffer(4);
        lightPos.put(new float[]{200.0f, 400.0f, 200.0f, 1.0f}).flip();
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_POSITION, lightPos);

        //ST sets the diffuse (base) color of the light to a warm yellowish-white
        FloatBuffer lightDiff = BufferUtils.createFloatBuffer(4);
        lightDiff.put(new float[]{1.0f, 0.9f, 0.7f, 1.0f}).flip();
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_DIFFUSE, lightDiff);

        //ST sets the specular (shine) color of the light to pure white for metallic highlights
        FloatBuffer lightSpec = BufferUtils.createFloatBuffer(4);
        lightSpec.put(new float[]{1.0f, 1.0f, 1.0f, 1.0f}).flip();
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_SPECULAR, lightSpec);

        //ST sets a low-level ambient light so unlit areas are not completely black
        FloatBuffer lightAmb = BufferUtils.createFloatBuffer(4);
        lightAmb.put(new float[]{0.2f, 0.2f, 0.25f, 1.0f}).flip();
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_AMBIENT, lightAmb);

        //ST enables smooth shading across triangles and auto-normalizes transformed normals
        GL11.glShadeModel(GL11.GL_SMOOTH);
        GL11.glEnable(GL11.GL_NORMALIZE);

        //JC loads the terrain mesh from an OBJ file and applies its texture
        terrain = new Terrain("fractal_terrain.obj", "terrain.png");

        //AV loads all impact sound files into the audio system for playback on bullet hits
        soundPlayer = new SoundPlayer();
        soundPlayer.loadAll(new String[]{
                "impact0.wav",
                "impact1.wav",
                "impact2.wav"
        });

        //JC initializes the bullet system with terrain bounds so bullets spawn within the terrain area
        bulletSystem = new BulletSystem(
                terrain.getMinX(), terrain.getMaxX(),
                terrain.getMinZ(), terrain.getMaxZ(),
                terrain,
                soundPlayer
        );
    }

    /*
     * This is the main game loop that runs continuously until the window is closed.
     * Each frame, we calculate delta time so movement stays consistent,
     * clear the screen, position the camera, render the terrain,
     * update and render all bullets, and then swap buffers to display the frame.
     */
    private void loop() {
        //JC records the start time in nanoseconds to calculate delta time each frame
        long lastTime = System.nanoTime();

        while (!GLFW.glfwWindowShouldClose(window)) {
            long now = System.nanoTime();
            //JC calculates delta time in seconds between frames for frame-rate-independent physics
            float dt = (now - lastTime) / 1_000_000_000.0f;
            lastTime = now;

            //ST clears the color and depth buffers at the start of each frame
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glLoadIdentity();

            //JC centers the camera view over the terrain based on its dimensions
            float centerX = terrain.getCenterX();
            float maxDim  = terrain.getMaxDimension();
            GL11.glTranslatef(-centerX, -maxDim * 0.24f, -maxDim * 1.4f);

            //ST enables color material and sets it to drive both ambient and diffuse lighting from vertex color
            GL11.glEnable(GL11.GL_COLOR_MATERIAL);
            GL11.glColorMaterial(GL11.GL_FRONT_AND_BACK, GL11.GL_AMBIENT_AND_DIFFUSE);
            terrain.render();

            //AV updates bullet physics and sound then renders all active bullets
            bulletSystem.update(dt);
            bulletSystem.render();

            //ST swaps the front and back buffers to display the rendered frame
            GLFW.glfwSwapBuffers(window);
            GLFW.glfwPollEvents();
        }
    }

    /*
     * This method sets up the camera’s perspective projection.
     * It defines how wide the field of view is, how the scene is scaled
     * based on the window size, and what range of depth is visible.
     */
    private void setPerspectiveProjection(float fov, float aspect, float zNear, float zFar) {
        //JC computes the frustum bounds from the field of view angle and aspect ratio
        float ymax = (float)(zNear * Math.tan(Math.toRadians(fov / 2.0)));
        float xmax = ymax * aspect;

        //JC loads the calculated frustum into the OpenGL projection matrix
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glFrustum(-xmax, xmax, -ymax, ymax, zNear, zFar);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
    }
}

/* ARTURO start
 * This class handles all bullet-related behavior in the scene.
 * It is responsible for spawning bullets over time, updating their physics,
 * handling collisions with the terrain, playing impact sounds,
 * and rendering them on screen.
 */
class BulletSystem {
    //ST defines the cap on how many bullets can exist in the scene at once
    private static final int   MAX_BULLETS  = 160;
    //ST defines how many bullets are spawned per second
    private static final float SPAWN_RATE   = 18.0f;
    //ST defines the downward speed bullets have when first spawned
    private static final float FALL_SPEED   = 18.0f;
    //ST defines the height above the terrain at which bullets are spawned
    private static final float SPAWN_HEIGHT = 200.0f;
    //JC defines the Y threshold below which a bullet is removed from the simulation
    private static final float KILL_Y       = -10.0f;
    //JC defines the uniform scale applied to small and large bullet models
    private static final float SMALL_SCALE = 1.2f;
    private static final float LARGE_SCALE = 5.2f;

    //JC physics constants controlling gravity strength and how energy is lost on each bounce
    private static final float GRAVITY             = 25.0f;
    private static final float BOUNCE_DAMPEN       = 0.35f;
    private static final float LATERAL_DAMPEN      = 0.82f;
    private static final float MIN_VERTICAL_BOUNCE = 1.2f;
    private static final int   MAX_BOUNCES         = 4;
    //AV small offset applied to keep the bullet sitting just above the terrain surface after landing
    private static final float SURFACE_OFFSET      = 0.08f;
    //AV how long in seconds a resting bullet stays visible before being removed
    private static final float REST_TIME           = 1.0f;
    //AV minimum vertical speed a bullet must have on impact before a sound is triggered
    private static final float MIN_IMPACT_SOUND_SPEED = 4.0f;

    private final List<Bullet> bullets = new ArrayList<>();
    private final Random rng = new Random();
    //ST accumulates fractional spawn ticks between frames to control the bullet spawn rate
    private float spawnAccumulator = 0.0f;

    private final float minX, maxX, minZ, maxZ;
    //JC mesh objects holding the loaded geometry for small and large bullet models
    private final OBJMesh smallMesh;
    private final OBJMesh largeMesh;
    //ST texture IDs for the small and large bullet surfaces loaded from image files
    private final int smallBulletTexture;
    private final int largeBulletTexture;
    private final Terrain terrain;
    private final SoundPlayer soundPlayer;

    /*
     * In the constructor, we initialize the bullet system by storing terrain boundaries,
     * loading the 3D models for both small and large bullets,
     * and loading their corresponding textures.
     */
    public BulletSystem(float minX, float maxX, float minZ, float maxZ, Terrain terrain, SoundPlayer soundPlayer) {
        //JC stores terrain boundary values used to clamp bullet positions during simulation
        this.minX = minX; this.maxX = maxX;
        this.minZ = minZ; this.maxZ = maxZ;
        this.terrain = terrain;
        this.soundPlayer = soundPlayer;

        //JC loads the OBJ model files for both bullet sizes into memory
        smallMesh = new OBJMesh("small_bullet.obj");
        largeMesh = new OBJMesh("large_bullet.obj");
        //ST loads the PNG textures for both bullet sizes and stores their OpenGL texture IDs
        smallBulletTexture = TextureLoader.load("small_bullet.png");
        largeBulletTexture = TextureLoader.load("large_bullet.png");
    }

    /*
     * This method runs every frame and updates all bullet behavior.
     * It first spawns new bullets based on a timed rate.
     *
     * Then, for each bullet, it applies physics by adding gravity,
     * updating position using velocity, and checking for collisions with the terrain.
     *
     * When a bullet hits the terrain, we calculate a bounce using the surface normal,
     * reduce its energy using dampening, and optionally play a sound if the impact is strong enough.
     *
     * If the bullet slows down too much or bounces too many times,
     * it transitions into a resting state and is eventually removed.
     */
    public void update(float dt) {
        //ST advances the spawn accumulator and spawns a new bullet each time it reaches a whole number
        spawnAccumulator += dt * SPAWN_RATE;
        while (spawnAccumulator >= 1.0f && bullets.size() < MAX_BULLETS) {
            spawnAccumulator -= 1.0f;
            spawnBullet();
        }

        bullets.removeIf(b -> {
            if (b.resting) {
                //AV counts down the rest timer and removes the bullet once it has settled long enough
                b.restTimer -= dt;
                return b.restTimer <= 0.0f;
            }
            //JC applies gravity to vertical velocity each frame
            float prevY = b.y;
            b.vy -= GRAVITY * dt;
            //JC integrates velocity into position to move the bullet forward each frame
            b.x += b.vx * dt;
            b.y += b.vy * dt;
            b.z += b.vz * dt;

            //JC clamps bullet position to stay within terrain bounds
            b.x = Math.max(minX, Math.min(maxX, b.x));
            b.z = Math.max(minZ, Math.min(maxZ, b.z));

            float terrainY = terrain.getHeightAt(b.x, b.z);

            //AV detects when a bullet crosses the terrain surface and triggers a bounce or rest
            if (prevY > terrainY && b.y <= terrainY && b.vy < 0.0f) {
                //AV plays a random impact sound only if the bullet is moving fast enough to warrant it
                if (Math.abs(b.vy) > MIN_IMPACT_SOUND_SPEED) {
                    soundPlayer.play();
                }

                //AV reflects the bullet velocity off the terrain surface normal to simulate a bounce
                float[] normal = terrain.getNormalAt(b.x, b.z);
                b.y = terrainY + SURFACE_OFFSET;
                b.bounceCount++;
                float dot = b.vx * normal[0] + b.vy * normal[1] + b.vz * normal[2];
                float rx  = b.vx - 2.0f * dot * normal[0];
                float ry  = b.vy - 2.0f * dot * normal[1];
                float rz  = b.vz - 2.0f * dot * normal[2];

                //AV dampens the reflected velocity to simulate energy loss on each bounce
                b.vx = rx * LATERAL_DAMPEN;
                b.vy = Math.abs(ry) * BOUNCE_DAMPEN;
                b.vz = rz * LATERAL_DAMPEN;

                //AV transitions bullet to resting state if the bounce is too weak or max bounces are reached
                if (b.vy < MIN_VERTICAL_BOUNCE || b.bounceCount >= MAX_BOUNCES) {
                    b.resting = true;
                    b.restTimer = REST_TIME;
                    b.vx = 0.0f;
                    b.vy = 0.0f;
                    b.vz = 0.0f;
                }
            }
            //JC removes the bullet from the list if it falls below the kill threshold
            return b.y < KILL_Y;
        });
    }

    /*
     * This method creates a new bullet at a random position within the terrain bounds.
     * It assigns a random horizontal velocity, a fixed downward velocity,
     * and randomly decides whether the bullet is small or large.
     */
    private void spawnBullet() {
        //ST picks a random X and Z position within the terrain bounds for the new bullet
        float x = minX + rng.nextFloat() * (maxX - minX);
        float z = minZ + rng.nextFloat() * (maxZ - minZ);
        //ST randomly assigns the bullet as large or small and gives it a slight random horizontal drift
        boolean large = rng.nextBoolean();
        float vx = (rng.nextFloat() - 0.5f) * 4.0f;
        float vz = (rng.nextFloat() - 0.5f) * 4.0f;
        //ST adds the new bullet to the active list at the spawn height with its initial velocity
        bullets.add(new Bullet(x, SPAWN_HEIGHT, z, large, vx, -FALL_SPEED, vz));
    }

    /*
     * This method is responsible for drawing all bullets.
     * For each bullet, we apply transformations like translation and rotation,
     * align the bullet with its direction of motion,
     * and then render the correct mesh and texture.
     *
     * We also apply lighting properties so the bullets appear shiny and metallic.
     */
    public void render() {
        //ST enables lighting and disables color material so per-material specular properties take effect
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_COLOR_MATERIAL);

        //ST sets the specular highlight color to white for a bright metallic shine on bullets
        FloatBuffer matSpec = BufferUtils.createFloatBuffer(4);
        matSpec.put(new float[]{1.0f, 1.0f, 1.0f, 1.0f}).flip();
        GL11.glMaterialfv(GL11.GL_FRONT, GL11.GL_SPECULAR, matSpec);

        //ST sets shininess to 110 for a tight, sharp specular glint simulating polished metal
        GL11.glMateriali(GL11.GL_FRONT, GL11.GL_SHININESS, 110);

        for (Bullet b : bullets) {
            //JC saves the current matrix, applies per-bullet transform, then restores it after rendering
            GL11.glPushMatrix();
            GL11.glTranslatef(b.x, b.y, b.z);

            if (!b.resting) {
                //JC rotates the bullet model to align with its current velocity direction while in flight
                float speed = (float) Math.sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz);
                if (speed > 0.01f) {
                    float pitch = (float) Math.toDegrees(Math.asin(-b.vy / speed));
                    float yaw   = (float) Math.toDegrees(Math.atan2(b.vx, b.vz));
                    GL11.glRotatef(yaw,   0, 1, 0);
                    GL11.glRotatef(pitch, 1, 0, 0);
                }
            } else {
                //JC flips the bullet upside down when resting to show it has landed
                GL11.glRotatef(180, 1, 0, 0);
            }

            if (b.large) {
                //JC scales and orients the large bullet mesh for rendering
                GL11.glScalef(LARGE_SCALE, LARGE_SCALE, LARGE_SCALE);
                GL11.glRotatef(90, 1, 0, 0);
                //ST binds the large bullet texture before rendering its mesh
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, largeBulletTexture);
                largeMesh.render();
            } else {
                //JC scales the small bullet mesh for rendering
                GL11.glScalef(SMALL_SCALE, SMALL_SCALE, SMALL_SCALE);
                //ST binds the small bullet texture before rendering its mesh
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, smallBulletTexture);
                smallMesh.render();
            }
            GL11.glPopMatrix();
        }

        //ST restores color material mode after bullet rendering is complete
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
    }

    /*
     * This inner class represents a single bullet.
     * It stores its position, velocity, size, bounce count,
     * and whether it is still moving or has come to rest.
     */
    private static class Bullet {
        float x, y, z, vx, vy, vz;
        boolean large, resting;
        int bounceCount;
        float restTimer;

        Bullet(float x, float y, float z, boolean large, float vx, float vy, float vz) {
            //ST stores the initial position, size flag, and velocity for a newly spawned bullet
            this.x = x;
            this.y = y;
            this.z = z;
            this.large = large;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            //AV initializes bounce tracking and rest timer fields to their default values
            this.resting = false;
            this.bounceCount = 0;
            this.restTimer = 0.0f;
        }
    }
}

/* JOSH start
 * This class is responsible for loading and rendering 3D models
 * from OBJ files.
 */
class OBJMesh {
    private final List<float[]> vertices  = new ArrayList<>();
    private final List<float[]> normals   = new ArrayList<>();
    private final List<float[]> texCoords = new ArrayList<>();
    private final List<int[]>   faces     = new ArrayList<>();

    /*
     * In the constructor, we read the OBJ file line by line.
     * We extract vertex positions, normals for lighting,
     * texture coordinates, and face definitions.
     *
     * This data is stored so it can later be sent to OpenGL for rendering.
     */
    public OBJMesh(String path) {
        //JC opens the OBJ file and reads it line by line to extract geometry data
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                //JC parses vertex normal vectors from lines beginning with "vn"
                if (line.startsWith("vn ")) {
                    String[] t = line.split("\\s+");
                    normals.add(new float[]{Float.parseFloat(t[1]), Float.parseFloat(t[2]), Float.parseFloat(t[3])});
                    //JC parses vertex positions from lines beginning with "v"
                } else if (line.startsWith("v ")) {
                    String[] t = line.split("\\s+");
                    vertices.add(new float[]{Float.parseFloat(t[1]), Float.parseFloat(t[2]), Float.parseFloat(t[3])});
                    //ST parses UV texture coordinates from lines beginning with "vt"
                } else if (line.startsWith("vt ")) {
                    String[] t = line.split("\\s+");
                    texCoords.add(new float[]{Float.parseFloat(t[1]), Float.parseFloat(t[2])});
                    //JC parses face definitions which index into vertices, UVs, and normals
                } else if (line.startsWith("f ")) {
                    String[] t = line.split("\\s+");
                    int[] face = new int[9];
                    for (int i = 0; i < 3; i++) {
                        String[] parts = t[i + 1].split("/");
                        face[i*3]     = Integer.parseInt(parts[0]) - 1;
                        face[i*3 + 1] = (parts.length > 1 && !parts[1].isEmpty()) ? Integer.parseInt(parts[1]) - 1 : -1;
                        face[i*3 + 2] = (parts.length > 2 && !parts[2].isEmpty()) ? Integer.parseInt(parts[2]) - 1 : -1;
                    }
                    faces.add(face);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        //JC adds fallback defaults if the OBJ file contained no texture coordinates or normals
        if (texCoords.isEmpty()) texCoords.add(new float[]{0, 0});
        if (normals.isEmpty())   normals.add(new float[]{0, 1, 0});
    }


    /*
     * This method renders the mesh by looping through each face
     * and sending its vertices to OpenGL as triangles.
     *
     * For each vertex, we apply its normal for lighting
     * and its texture coordinates so the texture maps correctly.
     */
    public void render() {
        //JC iterates over every face and submits its three vertices to the GPU as triangles
        GL11.glBegin(GL11.GL_TRIANGLES);
        for (int[] f : faces) {
            for (int i = 0; i < 3; i++) {
                int vni = f[i*3 + 2];
                int vti = f[i*3 + 1];
                int vi  = f[i*3];
                //ST submits the surface normal for this vertex so lighting calculates correctly
                if (vni >= 0) {
                    float[] n = normals.get(vni);
                    GL11.glNormal3f(n[0], n[1], n[2]);
                }
                //ST submits the UV coordinate for this vertex so the texture maps correctly onto the surface
                if (vti >= 0) {
                    float[] uv = texCoords.get(vti);
                    GL11.glTexCoord2f(uv[0], uv[1]);
                }
                float[] v = vertices.get(vi);
                GL11.glVertex3f(v[0], v[1], v[2]);
            }
        }
        GL11.glEnd();
    }

    public List<float[]> getVertices() { return vertices; }
    public List<float[]> getNormals()  { return normals;  }
    public List<int[]>   getFaces()    { return faces;    }
}

/*
 * This class handles loading image files and converting them into textures
 * that OpenGL can use.
 */
class TextureLoader {
    private static int nextPow2(int n) {
        int p = 1;
        //ST finds the next power of two size to satisfy OpenGL texture dimension requirements
        while (p < n) p <<= 1;
        return p;
    }

    /*
     * This method loads an image from disk, ensures its dimensions
     * are compatible with OpenGL, and converts it into a byte buffer.
     *
     * The image data is then uploaded to the GPU as a texture,
     * and the texture ID is returned so it can be used during rendering.
     */
    public static int load(String path) {
        try {
            //ST reads the image file from disk into a BufferedImage
            BufferedImage img = ImageIO.read(new File(path));
            int w = img.getWidth(), h = img.getHeight();
            int pw = nextPow2(w), ph = nextPow2(h);
            //ST rescales the image to power-of-two dimensions if it does not already meet them
            if (pw != w || ph != h) {
                BufferedImage sc = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
                sc.getGraphics().drawImage(img, 0, 0, pw, ph, null);
                img = sc;
                w = pw;
                h = ph;
            }
            int[] pixels = new int[w * h];
            img.getRGB(0, 0, w, h, pixels, 0, w);
            //ST converts the pixel data into a byte buffer in RGBA order for OpenGL upload
            java.nio.ByteBuffer buffer = BufferUtils.createByteBuffer(w * h * 4);
            for (int p : pixels) {
                buffer.put((byte)((p >> 16) & 0xFF));
                buffer.put((byte)((p >> 8)  & 0xFF));
                buffer.put((byte)(p         & 0xFF));
                buffer.put((byte)((p >> 24) & 0xFF));
            }
            buffer.flip();
            //ST generates a new OpenGL texture ID and uploads the image data with linear filtering
            int texId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
            return texId;
        } catch (IOException e) {
            //ST returns 0 as a null texture ID if the image file could not be loaded
            return 0;
        }
    }
}

/*
 * This class represents the terrain in the scene.
 * It is responsible for rendering the ground,
 * as well as providing height and surface normal data
 * for physics calculations like bullet collisions.
 */
class Terrain {
    private final OBJMesh mesh;
    private int textureId;
    private float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
    //JC scale factor that flattens terrain height to make the landscape less extreme
    private static final float TERRAIN_FLATTEN_SCALE = 0.35f;
    private int gridW, gridH;
    //JC flat array storing the sampled height value at each grid cell
    private float[] heightGrid;
    //AV flat array storing the averaged surface normal at each grid cell for bounce calculations
    private float[][] normalGrid;

    /*
     * In the constructor, we load the terrain mesh and its texture.
     * We also build a grid of height values and normals,
     * which allows fast lookup during physics calculations.
     */
    public Terrain(String objPath, String texturePath) {
        //JC loads the terrain OBJ mesh and its texture, then builds the height and normal lookup grids
        mesh = new OBJMesh(objPath);
        parseBounds(objPath);
        textureId = TextureLoader.load(texturePath);
        buildHeightGrid();
    }

    /*
     * This method converts the terrain mesh into a grid of height values.
     * It also averages normals at each grid cell so we can simulate smooth surfaces.
     *
     * This makes collision detection much faster than checking the full mesh.
     */
    private void buildHeightGrid() {
        //JC creates a 2D grid of height values sampled from terrain vertices for fast height lookup during physics
        gridW = Math.round(maxX - minX) + 1;
        gridH = Math.round(maxZ - minZ) + 1;
        heightGrid = new float[gridW * gridH];
        normalGrid = new float[gridW * gridH][];
        List<float[]> verts = mesh.getVertices();
        List<float[]> norms = mesh.getNormals();
        List<int[]> faces = mesh.getFaces();
        //AV temporary arrays used to accumulate and count normals before averaging them per cell
        float[][] normAcc = new float[gridW * gridH][3];
        int[] normCnt = new int[gridW * gridH];

        //JC maps each vertex to its nearest grid cell and stores its Y value as the cell height
        for (float[] v : verts) {
            int xi = Math.round(v[0] - minX), zi = Math.round(v[2] - minZ);
            if (xi >= 0 && xi < gridW && zi >= 0 && zi < gridH) {
                heightGrid[zi * gridW + xi] = v[1];
            }
        }

        //JC accumulates normals from all faces sharing each grid cell then averages and normalizes them
        for (int[] f : faces) {
            for (int i = 0; i < 3; i++) {
                int vi = f[i*3], vni = f[i*3+2];
                if (vi < 0 || vi >= verts.size()) continue;
                float[] v = verts.get(vi);
                int xi = Math.round(v[0]-minX), zi = Math.round(v[2]-minZ);
                if (xi >= 0 && xi < gridW && zi >= 0 && zi < gridH && vni >= 0 && vni < norms.size()) {
                    float[] n = norms.get(vni);
                    normAcc[zi*gridW+xi][0] += n[0];
                    normAcc[zi*gridW+xi][1] += n[1];
                    normAcc[zi*gridW+xi][2] += n[2];
                    normCnt[zi*gridW+xi]++;
                }
            }
        }

        //AV normalizes the accumulated normal vectors and stores them in the grid, defaulting to up if empty
        for (int i = 0; i < gridW*gridH; i++) {
            if (normCnt[i] > 0) {
                float nx = normAcc[i][0]/normCnt[i], ny = normAcc[i][1]/normCnt[i], nz = normAcc[i][2]/normCnt[i];
                float len = (float)Math.sqrt(nx*nx+ny*ny+nz*nz);
                normalGrid[i] = (len > 0) ? new float[]{nx/len, ny/len, nz/len} : new float[]{0,1,0};
            } else {
                normalGrid[i] = new float[]{0,1,0};
            }
        }
    }

    /*
     * This method returns the height of the terrain at a given position.
     * It uses interpolation between nearby grid points
     * so the terrain appears smooth instead of blocky.
     */
    public float getHeightAt(float wx, float wz) {
        //JC bilinearly interpolates between the four surrounding grid cells to get a smooth height value
        float gx = wx - minX, gz = wz - minZ;
        int x0 = (int)Math.floor(gx), z0 = (int)Math.floor(gz);
        int x1 = Math.min(gridW-1, x0+1), z1 = Math.min(gridH-1, z0+1);
        x0 = Math.max(0, Math.min(gridW-1, x0));
        z0 = Math.max(0, Math.min(gridH-1, z0));
        float tx = gx - x0, tz = gz - z0;
        float h00 = heightGrid[z0*gridW+x0], h10 = heightGrid[z0*gridW+x1], h01 = heightGrid[z1*gridW+x0], h11 = heightGrid[z1*gridW+x1];
        return (h00 + (h10 - h00) * tx + (h01 + (h11 - h01) * tx - (h00 + (h10 - h00) * tx)) * tz) * TERRAIN_FLATTEN_SCALE;
    }

    /*
     * This method returns the surface normal at a given position.
     * The normal is used to calculate realistic bounce directions.
     */
    public float[] getNormalAt(float wx, float wz) {
        //AV looks up the precomputed terrain normal at the nearest grid cell for use in bounce reflection
        int gx = Math.max(0, Math.min(gridW-1, Math.round(wx-minX)));
        int gz = Math.max(0, Math.min(gridH-1, Math.round(wz-minZ)));
        return normalGrid[gz*gridW+gx];
    }

    private void parseBounds(String path) {
        //JC reads through the OBJ file a second time to find the min and max X and Z extents of the terrain
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().startsWith("v ")) {
                    String[] t = line.trim().split("\\s+");
                    float x = Float.parseFloat(t[1]), z = Float.parseFloat(t[3]);
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
                }
            }
        } catch (IOException e) {
        }
    }

    /*
     * This method renders the terrain mesh.
     * It applies a vertical scale to flatten the terrain slightly,
     * binds the texture, and then draws the mesh.
     */
    public void render() {
        GL11.glPushMatrix();
        //ST applies the flatten scale on the Y axis to compress terrain height before rendering
        GL11.glScalef(1.0f, TERRAIN_FLATTEN_SCALE, 1.0f);
        //ST binds the terrain texture so it is applied across the mesh surface during rendering
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        mesh.render();
        GL11.glPopMatrix();
    }

    //JC returns the horizontal center of the terrain for camera positioning
    public float getCenterX() { return (minX+maxX)/2; }
    //JC returns the largest terrain dimension for scaling the camera distance
    public float getMaxDimension() { return Math.max(maxX-minX, maxZ-minZ); }
    //JC terrain boundary getters used by the bullet system to clamp spawn and movement positions
    public float getMinX() { return minX; }
    public float getMaxX() { return maxX; }
    public float getMinZ() { return minZ; }
    public float getMaxZ() { return maxZ; }
}

// AV: random impact audio system - loads 3 wav files and plays one random sound whenever a bullet hits terrain
/* ARTURO start
 * This class handles all sound effects in the game.
 * It loads multiple versions of impact sounds
 * and allows them to play simultaneously without cutting each other off.
 */
class SoundPlayer {
    //AV each sound file gets a pool of 8 clips so multiple impacts can overlap without cutting each other off
    private static final int CLIPS_PER_SOUND = 8;

    private Clip[][] clipPools;
    private int[] nextClipIndex;
    private final Random rng = new Random();

    /*
     * This method loads each sound file and creates multiple clip instances for it.
     * This allows multiple sounds to play at the same time,
     * which is important when many bullets hit at once.
     */
    public void loadAll(String[] paths) {
        try {
            //AV allocates a 2D pool array with one row per sound file and one clip per pool slot
            clipPools = new Clip[paths.length][CLIPS_PER_SOUND];
            nextClipIndex = new int[paths.length];

            for (int soundIndex = 0; soundIndex < paths.length; soundIndex++) {
                for (int clipIndex = 0; clipIndex < CLIPS_PER_SOUND; clipIndex++) {
                    //AV opens a fresh audio stream for each clip slot so they can all play independently
                    AudioInputStream audio = AudioSystem.getAudioInputStream(new File(paths[soundIndex]));
                    clipPools[soundIndex][clipIndex] = AudioSystem.getClip();
                    clipPools[soundIndex][clipIndex].open(audio);

                    //AV sets each clip to maximum gain so impact sounds are clearly audible
                    if (clipPools[soundIndex][clipIndex].isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                        FloatControl gain = (FloatControl) clipPools[soundIndex][clipIndex]
                                .getControl(FloatControl.Type.MASTER_GAIN);
                        gain.setValue(gain.getMaximum());
                    }
                }
            }
        } catch (Exception e) {
            //AV nulls out the clip pools if loading fails so play() can safely detect the error state
            clipPools = null;
            nextClipIndex = null;
            e.printStackTrace();
        }
    }

    /*
     * This method plays a random impact sound.
     * It selects a clip from the pool and restarts it from the beginning,
     * allowing for rapid and overlapping playback.
     */
    public void play() {
        if (clipPools == null || clipPools.length == 0) return;

        //AV picks a random sound file index so each impact plays a different clip for variety
        int soundIndex = rng.nextInt(clipPools.length);

        if (clipPools[soundIndex] == null) return;

        Clip[] pool = clipPools[soundIndex];
        int clipIndex = nextClipIndex[soundIndex];

        //AV guards against invalid pool state before attempting playback
        if (pool == null || clipIndex < 0 || clipIndex >= pool.length) return;
        if (pool[clipIndex] == null) return;

        Clip clip = pool[clipIndex];

        //AV stops the clip if it is already playing so it can be restarted from the beginning immediately
        if (clip.isRunning()) {
            clip.stop();
        }

        clip.setFramePosition(0);
        clip.start();

        //AV advances the pool index in a round-robin pattern to cycle through available clip slots
        nextClipIndex[soundIndex] = (clipIndex + 1) % pool.length;
    }
}