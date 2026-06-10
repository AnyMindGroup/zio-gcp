# Function calling

Provide a map of `FunctionDeclaration`s and the client will drive the [function-calling](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/tools/function-calling) loop for you: it forwards the declarations to the model as tools, invokes the matching function when the model requests a call, feeds the result back, and repeats until the model produces a final answer. Function parameter and return types are derived from ZIO Schema, just like [structured responses](./structured-responses):

<<< @/../examples/shared/src/main/scala/express_vertex_ai_function_calling.scala{scala}
