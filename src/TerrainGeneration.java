//javac -classpath ".;C:\Program Files\lwjgl-release-3.3.4-custom\*" TerrainGeneration.java
//java -classpath ".;C:\Program Files\lwjgl-release-3.3.4-custom\*" TerrainGeneration

import java.awt.image.BufferedImage; //ST
import java.io.BufferedReader; //ST
import java.io.File;
import java.io.FileReader; //ST
import java.io.IOException; //ST
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO; //ST

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

public class TerrainGeneration { //ST
    private long window;
    private int width = 800;
    private int height = 600;
    private Terrain terrain; //ST

    public static void main(String[] args) {
        new TerrainGeneration().run();
    }

    public void run() {
        init();
        loop();
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    private void init() { //ST
        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        window = GLFW.glfwCreateWindow(width, height, "Terrain Generation", 0, 0);
        if (window == 0) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        setPerspectiveProjection(45.0f, (float) width / height, 0.1f, 1000.0f); //ST: far plane
        GL11.glMatrixMode(GL11.GL_MODELVIEW);

        GL11.glEnable(GL11.GL_TEXTURE_2D); //ST

        terrain = new Terrain("fractal_terrain.obj", "terrain.png"); //ST: load OBJ + texture
    }

    private void loop() { //ST
        while (!GLFW.glfwWindowShouldClose(window)) {
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glLoadIdentity();

            // ST: adjust camera to see full terrain
            float centerX = terrain.getCenterX();
            float centerY = terrain.getCenterY();
            float maxDim = terrain.getMaxDimension();
            GL11.glTranslatef(-centerX, -5.0f, -maxDim * 1.5f);
            GL11.glRotatef(30, 1, 0, 0);

            terrain.render(); //ST

            GLFW.glfwSwapBuffers(window);
            GLFW.glfwPollEvents();
        }
    }

    private void setPerspectiveProjection(float fov, float aspect, float zNear, float zFar) { //book
        float ymax = (float)(zNear * Math.tan(Math.toRadians(fov / 2.0)));
        float xmax = ymax * aspect;

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glFrustum(-xmax, xmax, -ymax, ymax, zNear, zFar);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
    }
}

//ST: Terrain class for OBJ mesh + texture
class Terrain {
    private List<float[]> vertices = new ArrayList<>();
    private List<float[]> texCoords = new ArrayList<>();
    private List<int[]> faces = new ArrayList<>();
    private int textureId;

    private float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
    private float minZ = Float.MAX_VALUE, maxZ = Float.MIN_VALUE;

    public Terrain(String objPath, String texturePath) { //ST
        loadOBJ(objPath);
        textureId = loadTexture(texturePath);
    }

    private void loadOBJ(String path) { //ST
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("v ")) {
                    String[] tokens = line.split("\\s+");
                    float x = Float.parseFloat(tokens[1]);
                    float y = Float.parseFloat(tokens[2]);
                    float z = Float.parseFloat(tokens[3]);
                    vertices.add(new float[]{x, y, z});

                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
                } else if (line.startsWith("vt ")) {
                    String[] tokens = line.split("\\s+");
                    float u = Float.parseFloat(tokens[1]);
                    float v = Float.parseFloat(tokens[2]);
                    texCoords.add(new float[]{u, v});
                } else if (line.startsWith("f ")) {
                    String[] tokens = line.split("\\s+");
                    int[] face = new int[6]; // v1,vt1,v2,vt2,v3,vt3
                    for (int i = 0; i < 3; i++) {
                        String[] parts = tokens[i + 1].split("/");
                        face[i*2] = Integer.parseInt(parts[0]) - 1; // vertex index
                        if (parts.length > 1 && !parts[1].isEmpty()) {
                            face[i*2 + 1] = Integer.parseInt(parts[1]) - 1; // texcoord index
                        } else {
                            face[i*2 + 1] = 0; // default to first texcoord
                            if(texCoords.isEmpty()) texCoords.add(new float[]{0,0});
                        }
                    }
                    faces.add(face);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void render() { //ST
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glBegin(GL11.GL_TRIANGLES);
        for (int[] f : faces) {
            for (int i = 0; i < 3; i++) {
                float[] uv = texCoords.get(f[i*2 + 1]);
                float[] v = vertices.get(f[i*2]);
                GL11.glTexCoord2f(uv[0], uv[1]);
                GL11.glVertex3f(v[0], v[1], v[2]);
            }
        }
        GL11.glEnd();
    }

    private int loadTexture(String path) { //ST
        try {
            BufferedImage img = ImageIO.read(new File(path));
            int[] pixels = new int[img.getWidth() * img.getHeight()];
            img.getRGB(0, 0, img.getWidth(), img.getHeight(), pixels, 0, img.getWidth());

            FloatBuffer buffer = BufferUtils.createFloatBuffer(img.getWidth() * img.getHeight() * 3);
            for (int pixel : pixels) {
                buffer.put(((pixel >> 16) & 0xFF) / 255.0f);
                buffer.put(((pixel >> 8) & 0xFF) / 255.0f);
                buffer.put((pixel & 0xFF) / 255.0f);
            }
            buffer.flip();

            int texId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB8, img.getWidth(), img.getHeight(), 0,
                    GL12.GL_RGB, GL11.GL_FLOAT, buffer);
            return texId;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return 0;
    }

    //ST: helpers for camera
    public float getCenterX() { return (minX + maxX) / 2.0f; }
    public float getCenterY() { return (minZ + maxZ) / 2.0f; }
    public float getMaxDimension() { return Math.max(maxX - minX, maxZ - minZ); }
}