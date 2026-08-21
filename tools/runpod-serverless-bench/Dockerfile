FROM vllm/vllm-openai:v0.27.1

RUN python3 -m pip install --no-cache-dir runpod==1.12.0

COPY handler.py /opt/qwen38-serverless/handler.py

ENTRYPOINT ["python3", "/opt/qwen38-serverless/handler.py"]
